package com.adobe.aem.pindex.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.RowIterator;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Designate(ocd = PIndexFetcher.Configuration.class)
@Component(service = PIndexFetcher.class, immediate = true)
public class PIndexFetcher {

    private static final Logger log = LoggerFactory.getLogger(PIndexFetcher.class);
    private static final String SUBSERVICE = "pindex-fetcher";

    @ObjectClassDefinition(name = "Property Index Fetcher",
            description = "Warms up Oak property indexes by pulling their segments from cold storage")
    public @interface Configuration {

        @AttributeDefinition(name = "Node-type-based indexes",
                description = "indexNodeName;nodeType1,nodeType2 — for property indexes WITH declaringNodeTypes; "
                        + "runs one query per node type: SELECT [jcr:path] FROM [nodeType]")
        String[] nodeTypeIndexes() default {};

        @AttributeDefinition(name = "Property-based indexes",
                description = "indexNodeName;propName1,propName2 — for property indexes WITHOUT declaringNodeTypes; "
                        + "runs one query per property: SELECT [jcr:path] FROM [nt:base] WHERE [propName] IS NOT NULL")
        String[] propertyIndexes() default {};
    }

    // Delays activation until the subservice mapping registered by
    // ServiceUserMapperImpl.amended~pindex is live, preventing LoginException on startup.
    @Reference(target = "(subServiceName=" + SUBSERVICE + ")")
    private ServiceUserMapped serviceUserMapped;

    @Activate
    public PIndexFetcher(@Reference SlingSettingsService slingSettings,
            @Reference ResourceResolverFactory resolverFactory, Configuration config) {

        if (!slingSettings.getRunModes().contains("publish")) {
            log.info("Skipping warm-up: not a publish instance");
            return;
        }

        Map<String, Object> authInfo = new HashMap<>();
        authInfo.put(ResourceResolverFactory.SUBSERVICE, SUBSERVICE);

        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {
            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                log.error("Could not adapt ResourceResolver to Session");
                return;
            }

            String[] nodeTypeConfig = Optional.ofNullable(config.nodeTypeIndexes()).orElse(new String[0]);
            String[] propertyConfig = Optional.ofNullable(config.propertyIndexes()).orElse(new String[0]);

            if (nodeTypeConfig.length == 0 && propertyConfig.length == 0) {
                log.info("Skipping warm-up: no index configs provided");
                return;
            }

            warmUp(session.getWorkspace().getQueryManager(),
                    parseNodeTypeConfigs(nodeTypeConfig),
                    parsePropertyConfigs(propertyConfig));
        } catch (LoginException e) {
            log.error("Cannot obtain service resource resolver for subservice '{}'", SUBSERVICE, e);
        } catch (Exception e) {
            log.error("Error warming up property indexes", e);
        }
    }

    public List<NodeTypeIndex> parseNodeTypeConfigs(String[] configs) {
        Set<String> seen = new HashSet<>();
        List<NodeTypeIndex> result = new ArrayList<>();
        for (String item : configs) {
            String[] parts = item.split(";", 2);
            String indexName = parts[0];
            if (!seen.add(indexName)) {
                log.error("Duplicate index name '{}' in nodeTypeIndexes config", indexName);
                throw new IllegalArgumentException("Duplicate index name in nodeTypeIndexes: " + indexName);
            }
            result.add(new NodeTypeIndex(indexName, Arrays.asList(parts[1].split(","))));
        }
        return result;
    }

    public List<PropertyIndex> parsePropertyConfigs(String[] configs) {
        Set<String> seen = new HashSet<>();
        List<PropertyIndex> result = new ArrayList<>();
        for (String item : configs) {
            String[] parts = item.split(";", 2);
            String indexName = parts[0];
            if (!seen.add(indexName)) {
                log.error("Duplicate index name '{}' in propertyIndexes config", indexName);
                throw new IllegalArgumentException("Duplicate index name in propertyIndexes: " + indexName);
            }
            result.add(new PropertyIndex(indexName, Arrays.asList(parts[1].split(","))));
        }
        return result;
    }

    private void warmUp(QueryManager qm, List<NodeTypeIndex> nodeTypeIndexes,
            List<PropertyIndex> propertyIndexes) throws Exception {
        Query indexQuery = qm.createQuery(
                "SELECT [jcr:path] FROM [oak:QueryIndexDefinition] WHERE [type] = 'property'",
                Query.JCR_SQL2);
        NodeIterator indexNodes = indexQuery.execute().getNodes();
        while (indexNodes.hasNext()) {
            Node indexDef = indexNodes.nextNode();
            String indexName = indexDef.getName();
            String indexPath = indexDef.getPath();

            if (indexDef.hasProperty("reindex") && indexDef.getProperty("reindex").getBoolean()) {
                log.debug("Skipping '{}': reindex in progress", indexPath);
                continue;
            }

            String[] includedPaths = indexDef.hasProperty("includedPaths")
                    ? readStrings(indexDef, "includedPaths")
                    : new String[]{null};

            NodeTypeIndex nodeTypeIndex = nodeTypeIndexes.stream()
                    .filter(ai -> ai.getIndex().equals(indexName))
                    .findFirst().orElse(null);
            if (nodeTypeIndex != null) {
                for (String nodeType : nodeTypeIndex.getDeclaringNodeTypes()) {
                    for (String includedPath : includedPaths) {
                        log.info("Warming up '{}' by node type: type='{}', path='{}'",
                                indexPath, nodeType, includedPath);
                        runNodeTypeQuery(qm, indexPath, nodeType, includedPath);
                    }
                }
                continue;
            }

            PropertyIndex propIndex = propertyIndexes.stream()
                    .filter(pi -> pi.getIndex().equals(indexName))
                    .findFirst().orElse(null);
            if (propIndex != null) {
                for (String propName : propIndex.getPropertyNames()) {
                    for (String includedPath : includedPaths) {
                        log.info("Warming up '{}' by property: prop='{}', path='{}'",
                                indexPath, propName, includedPath);
                        runPropertyQuery(qm, indexPath, propName, includedPath);
                    }
                }
            }
        }
    }

    // Reads every node of the given type with no property filter — one pass warms
    // up all segments the property index covers for that declaring node type.
    private void runNodeTypeQuery(QueryManager qm, String indexPath,
            String nodeType, String includedPath) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT [jcr:path] FROM [").append(nodeType).append("]");
        if (includedPath != null && !"/".equals(includedPath)) {
            sql.append(" WHERE ISDESCENDANTNODE('").append(includedPath).append("')");
        }
        executeAndDrain(qm, indexPath, sql.toString());
    }

    // Used for property indexes with no declaringNodeType — scopes to nt:base
    // filtered by the specific property so we don't traverse the entire repository.
    private void runPropertyQuery(QueryManager qm, String indexPath,
            String propName, String includedPath) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT [jcr:path] FROM [nt:base]")
                .append(" WHERE [").append(propName).append("] IS NOT NULL");
        if (includedPath != null && !"/".equals(includedPath)) {
            sql.append(" AND ISDESCENDANTNODE('").append(includedPath).append("')");
        }
        executeAndDrain(qm, indexPath, sql.toString());
    }

    private void executeAndDrain(QueryManager qm, String indexPath, String sql) {
        try {
            NodeIterator nodes = qm.createQuery(sql, Query.JCR_SQL2).execute().getNodes();
            long count = 0;
            while (nodes.hasNext()) {
                nodes.nextNode();
                count++;
            }
            log.info("Warmed up '{}': {} nodes — {}", indexPath, count, sql);
        } catch (Exception e) {
            log.warn("Warm-up query failed for index '{}': {}", indexPath, sql, e);
        }
    }

    private static String[] readStrings(Node node, String propertyName) throws Exception {
        Property prop = node.getProperty(propertyName);
        if (prop.isMultiple()) {
            Value[] values = prop.getValues();
            String[] result = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                result[i] = values[i].getString();
            }
            return result;
        }
        return new String[]{prop.getValue().getString()};
    }
}

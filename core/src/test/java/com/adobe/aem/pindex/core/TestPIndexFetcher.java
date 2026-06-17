package com.adobe.aem.pindex.core;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.settings.SlingSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.Workspace;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TestPIndexFetcher {

    @Mock SlingSettingsService slingSettings;
    @Mock ResourceResolverFactory resolverFactory;
    @Mock PIndexFetcher.Configuration config;
    @Mock ResourceResolver resolver;
    @Mock Session session;
    @Mock Workspace workspace;
    @Mock QueryManager queryManager;
    @Mock Query discoveryQuery;
    @Mock QueryResult discoveryResult;
    @Mock NodeIterator nodeIterator;

    // ── parseNodeTypeConfigs ───────────────────────────────────────────────────

    @Test
    void parseNodeTypeConfigs_singleEntry_parsesIndexAndNodeType() {
        PIndexFetcher fetcher = fetcherOnAuthor();

        List<NodeTypeIndex> result = fetcher.parseNodeTypeConfigs(new String[]{"principalIndex;rep:User"});

        assertEquals(1, result.size());
        assertEquals("principalIndex", result.get(0).getIndex());
        assertEquals(List.of("rep:User"), result.get(0).getDeclaringNodeTypes());
    }

    @Test
    void parseNodeTypeConfigs_multipleNodeTypes_splitsOnComma() {
        PIndexFetcher fetcher = fetcherOnAuthor();

        List<NodeTypeIndex> result = fetcher.parseNodeTypeConfigs(
                new String[]{"principalIndex;rep:User,cq:Page,nt:unstructured"});

        assertEquals(List.of("rep:User", "cq:Page", "nt:unstructured"), result.get(0).getDeclaringNodeTypes());
    }

    @Test
    void parseNodeTypeConfigs_duplicateIndexName_throwsIllegalArgument() {
        PIndexFetcher fetcher = fetcherOnAuthor();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseNodeTypeConfigs(new String[]{
                    "indexA;rep:User",
                    "indexA;cq:Page"
                }));
        assertTrue(ex.getMessage().contains("indexA"));
    }

    @Test
    void parseNodeTypeConfigs_multipleEntries_returnsOnePerEntry() {
        PIndexFetcher fetcher = fetcherOnAuthor();

        List<NodeTypeIndex> result = fetcher.parseNodeTypeConfigs(new String[]{
            "indexA;rep:User",
            "indexB;cq:Page,nt:unstructured"
        });

        assertEquals(2, result.size());
        assertEquals("indexA", result.get(0).getIndex());
        assertEquals("indexB", result.get(1).getIndex());
        assertEquals(List.of("cq:Page", "nt:unstructured"), result.get(1).getDeclaringNodeTypes());
    }

    // ── parsePropertyConfigs ──────────────────────────────────────────────────

    @Test
    void parsePropertyConfigs_singleEntry_parsesIndexAndPropertyName() {
        PIndexFetcher fetcher = fetcherOnAuthor();

        List<PropertyIndex> result = fetcher.parsePropertyConfigs(new String[]{"myIndex;rep:principalName"});

        assertEquals(1, result.size());
        assertEquals("myIndex", result.get(0).getIndex());
        assertEquals(List.of("rep:principalName"), result.get(0).getPropertyNames());
    }

    @Test
    void parsePropertyConfigs_multipleProperties_splitsOnComma() {
        PIndexFetcher fetcher = fetcherOnAuthor();

        List<PropertyIndex> result = fetcher.parsePropertyConfigs(
                new String[]{"myIndex;rep:principalName,jcr:uuid,customProp"});

        assertEquals(List.of("rep:principalName", "jcr:uuid", "customProp"), result.get(0).getPropertyNames());
    }

    @Test
    void parsePropertyConfigs_duplicateIndexName_throwsIllegalArgument() {
        PIndexFetcher fetcher = fetcherOnAuthor();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fetcher.parsePropertyConfigs(new String[]{
                    "indexA;propA",
                    "indexA;propB"
                }));
        assertTrue(ex.getMessage().contains("indexA"));
    }

    @Test
    void parsePropertyConfigs_multipleEntries_returnsOnePerEntry() {
        PIndexFetcher fetcher = fetcherOnAuthor();

        List<PropertyIndex> result = fetcher.parsePropertyConfigs(new String[]{
            "indexA;propA",
            "indexB;propB,propC"
        });

        assertEquals(2, result.size());
        assertEquals("indexA", result.get(0).getIndex());
        assertEquals("indexB", result.get(1).getIndex());
        assertEquals(List.of("propB", "propC"), result.get(1).getPropertyNames());
    }

    // ── activation early-exit paths ────────────────────────────────────────────

    @Test
    void activate_notPublishInstance_neverOpensResolver() throws Exception {
        when(slingSettings.getRunModes()).thenReturn(Set.of("author"));

        new PIndexFetcher(slingSettings, resolverFactory, config);

        verifyNoInteractions(resolverFactory);
    }

    @Test
    void activate_emptyIndexConfig_neverTouchesSession() throws Exception {
        when(slingSettings.getRunModes()).thenReturn(Set.of("publish"));
        when(resolverFactory.getServiceResourceResolver(any())).thenReturn(resolver);
        when(resolver.adaptTo(Session.class)).thenReturn(session);
        // nodeTypeIndexes() and propertyIndexes() return null (Mockito default) → treated as empty

        new PIndexFetcher(slingSettings, resolverFactory, config);

        verifyNoInteractions(session);
    }

    @Test
    void activate_loginException_doesNotThrow() throws Exception {
        when(slingSettings.getRunModes()).thenReturn(Set.of("publish"));
        when(resolverFactory.getServiceResourceResolver(any()))
                .thenThrow(new LoginException("no service user"));

        assertDoesNotThrow(() -> new PIndexFetcher(slingSettings, resolverFactory, config));
    }

    @Test
    void activate_nullSession_doesNotThrow() throws Exception {
        when(slingSettings.getRunModes()).thenReturn(Set.of("publish"));
        when(resolverFactory.getServiceResourceResolver(any())).thenReturn(resolver);
        when(resolver.adaptTo(Session.class)).thenReturn(null);

        assertDoesNotThrow(() -> new PIndexFetcher(slingSettings, resolverFactory, config));
    }

    // ── warmUp: index node filtering ──────────────────────────────────────────

    @Test
    void warmUp_noIndexNodes_runsOnlyDiscoveryQuery() throws Exception {
        setupPublishWithQueryManager();
        when(config.nodeTypeIndexes()).thenReturn(new String[]{"testIndex;rep:User"});
        when(nodeIterator.hasNext()).thenReturn(false);

        new PIndexFetcher(slingSettings, resolverFactory, config);

        verify(queryManager, times(1)).createQuery(anyString(), anyString());
    }

    @Test
    void warmUp_reindexInProgress_skipsWarmUpQuery() throws Exception {
        setupPublishWithQueryManager();
        when(config.nodeTypeIndexes()).thenReturn(new String[]{"testIndex;rep:User"});

        Node indexNode = mock(Node.class);
        when(indexNode.getPath()).thenReturn("/oak:index/testIndex");
        when(indexNode.getName()).thenReturn("testIndex");
        when(indexNode.hasProperty("reindex")).thenReturn(true);
        Property reindexProp = mock(Property.class);
        when(reindexProp.getBoolean()).thenReturn(true);
        when(indexNode.getProperty("reindex")).thenReturn(reindexProp);

        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);

        new PIndexFetcher(slingSettings, resolverFactory, config);

        verify(queryManager, times(1)).createQuery(anyString(), anyString());
    }

    @Test
    void warmUp_indexNotInEitherList_skipsWarmUpQuery() throws Exception {
        setupPublishWithQueryManager();
        when(config.nodeTypeIndexes()).thenReturn(new String[]{"otherIndex;rep:User"});

        Node indexNode = mock(Node.class);
        when(indexNode.getPath()).thenReturn("/oak:index/testIndex");
        when(indexNode.getName()).thenReturn("testIndex");
        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);

        new PIndexFetcher(slingSettings, resolverFactory, config);

        verify(queryManager, times(1)).createQuery(anyString(), anyString());
    }

    // ── warmUp: node-type mode ────────────────────────────────────────────────

    @Test
    void warmUp_nodeTypeMode_querySelectsAllNodesOfType() throws Exception {
        setupPublishWithQueryManager();
        when(config.nodeTypeIndexes()).thenReturn(new String[]{"testIndex;rep:User"});

        Node indexNode = buildIndexNode("/oak:index/testIndex", null);
        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);
        stubWarmUpQuery("rep:User");

        new PIndexFetcher(slingSettings, resolverFactory, config);

        // SELECT [jcr:path] FROM [rep:User] — no WHERE condition, reads every indexed node
        String sql = captureWarmUpQuery();
        assertTrue(sql.contains("[rep:User]"));
        assertFalse(sql.contains("IS NOT NULL"));
        assertFalse(sql.contains("ISDESCENDANTNODE"));
    }

    @Test
    void warmUp_nodeTypeMode_includedPath_appendsIsdescendantnode() throws Exception {
        setupPublishWithQueryManager();
        when(config.nodeTypeIndexes()).thenReturn(new String[]{"testIndex;rep:User"});

        Node indexNode = buildIndexNode("/oak:index/testIndex", new String[]{"/home/users"});
        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);
        stubWarmUpQuery("ISDESCENDANTNODE");

        new PIndexFetcher(slingSettings, resolverFactory, config);

        String sql = captureWarmUpQuery();
        assertTrue(sql.contains("ISDESCENDANTNODE('/home/users')"));
    }

    @Test
    void warmUp_nodeTypeMode_multipleNodeTypes_runsOneQueryPerType() throws Exception {
        setupPublishWithQueryManager();
        when(config.nodeTypeIndexes()).thenReturn(new String[]{"testIndex;rep:User,cq:Page"});

        Node indexNode = buildIndexNode("/oak:index/testIndex", null);
        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);
        stubWarmUpQuery("rep:User");
        stubWarmUpQuery("cq:Page");

        new PIndexFetcher(slingSettings, resolverFactory, config);

        // 1 discovery + 2 warm-up queries (one per config node type, no property loop)
        verify(queryManager, times(3)).createQuery(anyString(), eq(Query.JCR_SQL2));
    }

    // ── warmUp: property mode ─────────────────────────────────────────────────

    @Test
    void warmUp_propertyMode_queryUsesNtBaseAndConfigProperty() throws Exception {
        setupPublishWithQueryManager();
        when(config.propertyIndexes()).thenReturn(new String[]{"testIndex;myCustomProp"});

        Node indexNode = buildIndexNode("/oak:index/testIndex", null);
        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);
        stubWarmUpQuery("myCustomProp");

        new PIndexFetcher(slingSettings, resolverFactory, config);

        String sql = captureWarmUpQuery();
        assertTrue(sql.contains("[nt:base]"));
        assertTrue(sql.contains("[myCustomProp] IS NOT NULL"));
        assertFalse(sql.contains("ISDESCENDANTNODE"));
    }

    @Test
    void warmUp_propertyMode_includedPath_appendsIsdescendantnode() throws Exception {
        setupPublishWithQueryManager();
        when(config.propertyIndexes()).thenReturn(new String[]{"testIndex;myCustomProp"});

        Node indexNode = buildIndexNode("/oak:index/testIndex", new String[]{"/content/mysite"});
        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);
        stubWarmUpQuery("ISDESCENDANTNODE");

        new PIndexFetcher(slingSettings, resolverFactory, config);

        String sql = captureWarmUpQuery();
        assertTrue(sql.contains("ISDESCENDANTNODE('/content/mysite')"));
    }

    @Test
    void warmUp_propertyMode_multipleProperties_runsOneQueryPerProperty() throws Exception {
        setupPublishWithQueryManager();
        when(config.propertyIndexes()).thenReturn(new String[]{"testIndex;propA,propB"});

        Node indexNode = buildIndexNode("/oak:index/testIndex", null);
        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);
        stubWarmUpQuery("[prop");

        new PIndexFetcher(slingSettings, resolverFactory, config);

        // 1 discovery + 2 warm-up queries (one per config property)
        verify(queryManager, times(3)).createQuery(anyString(), eq(Query.JCR_SQL2));
    }

    @Test
    void warmUp_queryExecutionFailure_doesNotThrow() throws Exception {
        setupPublishWithQueryManager();
        when(config.nodeTypeIndexes()).thenReturn(new String[]{"testIndex;rep:User"});

        Node indexNode = buildIndexNode("/oak:index/testIndex", null);
        when(nodeIterator.hasNext()).thenReturn(true, false);
        when(nodeIterator.nextNode()).thenReturn(indexNode);

        Query failingQuery = mock(Query.class);
        when(failingQuery.execute()).thenThrow(new RuntimeException("query engine error"));
        when(queryManager.createQuery(contains("rep:User"), eq(Query.JCR_SQL2))).thenReturn(failingQuery);

        assertDoesNotThrow(() -> new PIndexFetcher(slingSettings, resolverFactory, config));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PIndexFetcher fetcherOnAuthor() {
        when(slingSettings.getRunModes()).thenReturn(Set.of("author"));
        return new PIndexFetcher(slingSettings, resolverFactory, config);
    }

    private void setupPublishWithQueryManager() throws Exception {
        when(slingSettings.getRunModes()).thenReturn(Set.of("publish"));
        when(resolverFactory.getServiceResourceResolver(any())).thenReturn(resolver);
        when(resolver.adaptTo(Session.class)).thenReturn(session);
        when(session.getWorkspace()).thenReturn(workspace);
        when(workspace.getQueryManager()).thenReturn(queryManager);
        when(queryManager.createQuery(
                contains("oak:QueryIndexDefinition"), eq(Query.JCR_SQL2)))
                .thenReturn(discoveryQuery);
        when(discoveryQuery.execute()).thenReturn(discoveryResult);
        when(discoveryResult.getNodes()).thenReturn(nodeIterator);
    }

    // Neither mode reads propertyNames from the JCR node; node types (for node-type
    // mode) come from the config and properties (for property mode) also from config.
    private Node buildIndexNode(String path, String[] includedPaths) throws Exception {
        Node node = mock(Node.class);
        when(node.getPath()).thenReturn(path);
        when(node.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
        when(node.hasProperty("reindex")).thenReturn(false);
        if (includedPaths != null) {
            when(node.hasProperty("includedPaths")).thenReturn(true);
            Property includedPathsMock = multiValueProp(includedPaths);
            when(node.getProperty("includedPaths")).thenReturn(includedPathsMock);
        } else {
            when(node.hasProperty("includedPaths")).thenReturn(false);
        }
        return node;
    }

    private Property multiValueProp(String[] values) throws Exception {
        Property prop = mock(Property.class);
        when(prop.isMultiple()).thenReturn(true);
        Value[] jcrValues = new Value[values.length];
        for (int i = 0; i < values.length; i++) {
            Value v = mock(Value.class);
            when(v.getString()).thenReturn(values[i]);
            jcrValues[i] = v;
        }
        when(prop.getValues()).thenReturn(jcrValues);
        return prop;
    }

    private void stubWarmUpQuery(String containing) throws Exception {
        Query q = mock(Query.class);
        QueryResult result = mock(QueryResult.class);
        NodeIterator nodes = mock(NodeIterator.class);
        when(q.execute()).thenReturn(result);
        when(result.getNodes()).thenReturn(nodes);
        when(nodes.hasNext()).thenReturn(false);
        when(queryManager.createQuery(contains(containing), eq(Query.JCR_SQL2))).thenReturn(q);
    }

    private String captureWarmUpQuery() throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(queryManager, times(2)).createQuery(captor.capture(), eq(Query.JCR_SQL2));
        // index 0 = discovery query, index 1 = warm-up query
        return captor.getAllValues().get(1);
    }
}

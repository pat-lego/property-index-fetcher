# pindex — Property Index Fetcher

AEM publish instances running on AEMaaCS (Cloud Service) use cloud blob storage for the Oak segment store. On a fresh instance or after a failover, segments are fetched on demand as content is accessed. This causes slow first-touch queries because the property index B-trees are cold.

This bundle runs on startup on publish and forces those segments into the local cache by executing targeted JCR-SQL2 queries that drive Oak to traverse each property index.

## How it works

On activation (`@Component(immediate = true)`), `PIndexFetcher` runs once and exits. It does not run on author.

### Activation ordering

The bundle binds a `@Reference` to a `ServiceUserMapped` marker service (scoped to the `pindex-fetcher` subservice name). This creates an OSGi dependency that delays activation until the repoinit service user mapping is registered, preventing a `LoginException` on startup before the subservice user exists.

### Two warm-up modes

Each configured index falls into one of two modes depending on whether the Oak index has a `declaringNodeTypes` constraint.

#### Node-type mode — indexes WITH declaringNodeTypes

Config field: `nodeTypeIndexes`  
Format: `indexNodeName;nodeType1,nodeType2`

```
nodetype;rep:User,cq:ClientLibraryFolder
```

For each declaring node type, runs:

```sql
SELECT [jcr:path] FROM [rep:User]
```

No `WHERE` condition. One query reads every node of that type, pulling all segments the property index covers for that type in a single pass. If the index has `includedPaths` set in JCR, the query is scoped:

```sql
SELECT [jcr:path] FROM [rep:User] WHERE ISDESCENDANTNODE('/home/users')
```

#### Property mode — indexes WITHOUT declaringNodeTypes

Config field: `propertyIndexes`  
Format: `indexNodeName;propName1,propName2`

```
uuid;jcr:uuid
```

For each property name, runs:

```sql
SELECT [jcr:path] FROM [nt:base] WHERE [customTag] IS NOT NULL
```

The `WHERE` clause is required here to avoid a full repository scan on `nt:base`. Property names come from the config, not from the JCR index node, so the admin explicitly controls what gets queried. If the index has `includedPaths` in JCR:

```sql
SELECT [jcr:path] FROM [nt:base] WHERE [customTag] IS NOT NULL AND ISDESCENDANTNODE('/content')
```

### Skipped indexes

- Index node is in neither config list → skipped silently
- `reindex` property is `true` on the index node → skipped (index is being rebuilt, segments are in flux)
- Query execution fails → logged as WARN, warm-up continues with remaining indexes

### Duplicate config protection

Declaring the same index name twice in either config field throws `IllegalArgumentException` on activation and logs an error, preventing a misconfigured warm-up from running partially.

## OSGi configuration

All three config files live under `ui.config/src/main/content/jcr_root/apps/pindex/osgiconfig/config.publish/`.

### `com.adobe.aem.pindex.core.PIndexFetcher.cfg.json`

```json
{
  "nodeTypeIndexes": [
    "nodetype;rep:User,cq:ClientLibraryFolder"
  ],
  "propertyIndexes": [
    "uuid;jcr:uuid"
  ]
}
```

Each entry is a string: `indexNodeName;value1,value2`. The index node name must match the Oak index definition node name under `/oak:index` exactly.

### `org.apache.sling.jcr.repoinit.RepositoryInitializer~pindex.cfg.json`

Creates the service user and grants it the read access it needs to traverse index nodes and content:

```json
{
  "scripts": [
    "create service user pindex-fetcher with path /home/users/system/cq:services/internal",
    "set ACL on /oak:index\n    allow jcr:read for pindex-fetcher\nend",
    "set ACL on /content\n    allow jcr:read for pindex-fetcher\nend",
    "set ACL on /conf\n    allow jcr:read for pindex-fetcher\nend",
    "set ACL on /home\n    allow jcr:read for pindex-fetcher\nend"
  ]
}
```

### `org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~pindex.cfg.json`

Registers the `pindex-fetcher` subservice name so the bundle can open a resource resolver. This is also what the `@Reference(target = "(subServiceName=pindex-fetcher)")` on `ServiceUserMapped` waits for before allowing the component to activate:

```json
{
  "user.mapping": [
    "pindex.core:pindex-fetcher=pindex-fetcher"
  ]
}
```

## Build

```
mvn clean install -PautoInstallBundle
```

Run unit tests only:

```
mvn clean test -pl core
```

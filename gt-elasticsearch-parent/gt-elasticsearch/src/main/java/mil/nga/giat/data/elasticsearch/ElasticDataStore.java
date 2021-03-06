/**
 * This file is hereby placed into the Public Domain. This means anyone is
 * free to do whatever they wish with this file.
 */
package mil.nga.giat.data.elasticsearch;

import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.joda.Joda;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.node.Node;
import org.geotools.data.FeatureReader;
import org.geotools.data.Query;
import org.geotools.data.Transaction;
import org.geotools.data.store.ContentDataStore;
import org.geotools.data.store.ContentEntry;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.feature.NameImpl;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.Name;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;

import mil.nga.giat.data.elasticsearch.ElasticAttribute.ElasticGeometryType;

/**
 * A data store for an Elasticsearch index containing geo_point or geo_shape
 * types.
 *
 */
public class ElasticDataStore extends ContentDataStore {

    private final static Logger LOGGER = Logging.getLogger(ElasticDataStoreFactory.class);

    private final String indexName;

    private final String searchIndices;
   
    private final Node node;

    private final Client client;
    
    private final boolean isLocal;

    private final List<Name> baseTypeNames;
    
    private final Map<Name, String> docTypes;

    private Map<String, ElasticLayerConfiguration> layerConfigurations;

    private boolean sourceFilteringEnabled;

    private Integer defaultMaxFeatures;

    private Long scrollSize;
    
    private boolean scrollEnabled;
    
    private Integer scrollTime;

    public ElasticDataStore(String searchHost, Integer hostPort, 
            String indexName, String searchIndices, String clusterName,
            boolean localNode, boolean storeData, String dataPath) {

        LOGGER.fine("initializing data store " + searchHost + ":" + hostPort + "/" + indexName);

        this.indexName = indexName;
        
        if (searchIndices != null) {
            this.searchIndices = searchIndices;
        } else {
            this.searchIndices = indexName;
        }

        final ElasticCompat compat = ElasticCompatLoader.getCompat(null);
        
        if (dataPath != null) {
            Settings settings = compat.createSettings("path.home", dataPath, "http.enabled", false);
            node = nodeBuilder()
                    .settings(settings)
                    .local(true)
                    .clusterName(clusterName)
                    .node();
            client = node.client();
            isLocal = true;
        } else if (localNode) {
            Path path = null;
            try {
                path = Files.createTempDirectory("gt_es");
            } catch (IOException e) {
                throw new RuntimeException("unable to create temp director for path.home", e);
            }
            Settings settings = compat.createSettings("path.home", path);
            node = nodeBuilder()
                    .settings(settings)
                    .data(storeData)
                    .clusterName(clusterName)
                    .node();
            client = node.client();
            isLocal = false;
        } else {
            final TransportAddress address;
            address = new InetSocketTransportAddress(getInetAddress(searchHost), hostPort);
            Settings settings = compat.createSettings("cluster.name", clusterName);
            client = compat.createClient(settings, address);
            node = null;
            isLocal = false;
        }
        LOGGER.fine("client connection established");

        final ClusterStateRequest clusterStateRequest;
        clusterStateRequest = Requests.clusterStateRequest()
                .local(isLocal)
                .indices(indexName);

        LOGGER.fine("querying cluster state");
        final ClusterState state;
        state = client.admin()
                .cluster()
                .state(clusterStateRequest)
                .actionGet().getState();

        IndexMetaData metadata = state.metaData().index(indexName);
        if (metadata != null) {
            final ImmutableOpenMap<String, MappingMetaData> mappings;
            mappings = state.metaData().index(indexName).getMappings();
            final Iterator<String> elasticTypes = mappings.keysIt();
            final Vector<Name> names = new Vector<Name>();
            while (elasticTypes.hasNext()) {
                names.add(new NameImpl(elasticTypes.next()));
            }
            baseTypeNames = names;
        } else {
            baseTypeNames = new ArrayList<>();
        }
        
        layerConfigurations = new ConcurrentHashMap<>();
        docTypes = new HashMap<>();
    }

    private InetAddress getInetAddress(String searchHost) {
        try {
            return InetAddress.getByName(searchHost);
        } catch (UnknownHostException e) {
//            LOGGER.severe(e.getLocalizedMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    protected List<Name> createTypeNames() {
        final List<Name> names = new ArrayList<>();
        names.addAll(baseTypeNames);
        names.addAll(docTypes.keySet());
        return names;
    }

    @Override
    protected ContentFeatureSource createFeatureSource(ContentEntry entry) throws IOException {
        return new ElasticFeatureSource(entry, Query.ALL);
    }

    @Override
    public ContentFeatureSource getFeatureSource(Name name, Transaction tx)
            throws IOException {

        ElasticLayerConfiguration layerConfig = layerConfigurations.get(name.getLocalPart());
        if (layerConfig != null) {
            docTypes.put(name, layerConfig.getDocType());
        }
        ContentFeatureSource featureSource = super.getFeatureSource(name, tx);
        featureSource.getEntry().getState(Transaction.AUTO_COMMIT).flush();
        
        return featureSource;
    }

    @Override
    public FeatureReader<SimpleFeatureType, SimpleFeature> getFeatureReader(Query query, 
            Transaction tx) throws IOException {
        return super.getFeatureReader(query, tx);
    }

    public List<ElasticAttribute> getElasticAttributes(Name layerName) throws IOException {
        final String localPart = layerName.getLocalPart();
        ElasticLayerConfiguration layerConfig = layerConfigurations.get(localPart);
        final List<ElasticAttribute> elasticAttributes;
        if (layerConfig == null || layerConfig.getAttributes().isEmpty()) {
            final String docType;
            if (docTypes.containsKey(layerName)) {
                docType = docTypes.get(layerName);
            } else {
                docType = localPart;
            }

            final ClusterStateRequest clusterStateRequest;
            clusterStateRequest = Requests.clusterStateRequest()
                    .routingTable(true)
                    .nodes(true)
                    .local(isLocal)
                    .indices(indexName);

            final ClusterState state;
            state = client.admin().cluster()
                    .state(clusterStateRequest).actionGet().getState();
            final MappingMetaData metadata;
            metadata = state.metaData().index(indexName)
                    .mapping(docType);

            elasticAttributes = new ArrayList<ElasticAttribute>();
            if (metadata != null) {
                final byte[] mappingSource = metadata.source().uncompressed();
                final XContentParser parser;
                parser = XContentFactory.xContent(mappingSource)
                        .createParser(mappingSource);

                Map<String, Object> mapping = parser.map();
                if (mapping.size() == 1 && mapping.containsKey(docType)) {
                    // the type name is the root value, reduce it
                    mapping = (Map<String, Object>) mapping.get(docType);
                }

                add(elasticAttributes, "_id", "string", mapping, false);
                add(elasticAttributes, "_index", "string", mapping, false);
                add(elasticAttributes, "_type", "string", mapping, false);
                add(elasticAttributes, "_score", "float", mapping, false);
                add(elasticAttributes, "_relative_score", "float", mapping, false);

                walk(elasticAttributes, mapping, "", false, false);

                // add default geometry and short name and count duplicate short names
                final Map<String,Integer> counts = new HashMap<>();
                boolean foundGeometry = false;
                for (final ElasticAttribute attribute : elasticAttributes) {
                    if (!foundGeometry && Geometry.class.isAssignableFrom(attribute.getType())) {
                        attribute.setDefaultGeometry(true);
                        foundGeometry = true;
                    }
                    final String[] parts = attribute.getName().split("\\.");
                    attribute.setShortName(parts[parts.length-1]);
                    final int count;
                    if (counts.containsKey(attribute.getShortName())) {
                        count = counts.get(attribute.getShortName())+1;
                    } else {
                        count = 1;
                    }
                    counts.put(attribute.getShortName(), count);
                }
                // use full name if short name has duplicates
                for (final ElasticAttribute attribute : elasticAttributes) {
                    if (counts.get(attribute.getShortName()) > 1) {
                        attribute.setShortName(attribute.getName());
                    }
                }
            }
        } else {
            elasticAttributes = layerConfig.getAttributes();
        }
        return elasticAttributes;
    }
    
    @Override
    public void dispose() {
        LOGGER.fine("closing client");
        this.client.close();
        if (this.node != null) {
            LOGGER.fine("closing node");
            this.node.close();
        }
        super.dispose();
    }

    public String getIndexName() {
        return indexName;
    }

    public String getSearchIndices() {
        return searchIndices;
    }

    public Client getClient() {
        return client;
    }

    public boolean isSourceFilteringEnabled() {
        return sourceFilteringEnabled;
    }

    public void setSourceFilteringEnabled(boolean sourceFilteringEnabled) {
        this.sourceFilteringEnabled = sourceFilteringEnabled;
    }

    public Integer getDefaultMaxFeatures() {
        return defaultMaxFeatures;
    }

    public void setDefaultMaxFeatures(Integer defaultMaxFeatures) {
        this.defaultMaxFeatures = defaultMaxFeatures;
    }

    public Long getScrollSize() {
        return scrollSize;
    }

    public Boolean getScrollEnabled() {
        return scrollEnabled;
    }

    public Integer getScrollTime() {
        return scrollTime;
    }

    public void setScrollSize(Long scrollSize) {
        this.scrollSize = scrollSize;
    }

    public void setScrollEnabled(Boolean scrollEnabled) {
        this.scrollEnabled = scrollEnabled;
    }

    public void setScrollTime(Integer scrollTime) {
        this.scrollTime = scrollTime;
    }

    public Map<String, ElasticLayerConfiguration> getLayerConfigurations() {
        return layerConfigurations;
    }

    public void setLayerConfiguration(ElasticLayerConfiguration layerConfig) {
        final String layerName = layerConfig.getLayerName();
        this.layerConfigurations.put(layerName, layerConfig);
    }
    
    public Map<Name, String> getDocTypes() {
        return docTypes;
    }

    public String getDocType(Name typeName) {
        final String docType;
        if (docTypes.containsKey(typeName)) {
            docType = docTypes.get(typeName);
        } else {
            docType = typeName.getLocalPart();
        }
        return docType;
    }    

    private void walk(List<ElasticAttribute> elasticAttributes, Map<String,Object> map, 
            String propertyKey, boolean startType, boolean nested) {
        for (final Map.Entry<String, Object> entry : map.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            if (!key.equals("_timestamp") && Map.class.isAssignableFrom(value.getClass())) {
                final String newPropertyKey;
                if (!startType && key.equals("properties")) {
                    newPropertyKey = propertyKey;
                } else if (propertyKey.isEmpty()) {
                    newPropertyKey = entry.getKey();
                } else {
                    newPropertyKey = propertyKey + "." + key;
                }
                startType = !startType && key.equals("properties");
                if (!nested && map.containsKey("type")) {
                    nested = map.get("type").equals("nested");
                }

                if (ElasticParserUtil.isGeoPointFeature((Map) value)) {
                    add(elasticAttributes, propertyKey + ".coordinates", "geo_point", (Map) value, nested);
                } else {
                    walk(elasticAttributes, (Map) value, newPropertyKey, startType, nested);
                }
            } else if (key.equals("type") && !value.equals("nested")) {
                add(elasticAttributes, propertyKey, (String) value, map, nested);
            } else if (key.equals("_timestamp")) {
                add(elasticAttributes, "_timestamp", "date", map, nested);
            }
        }
    }

    private void add(List<ElasticAttribute> elasticAttributes, String propertyKey, 
            String propertyType, Map<String,Object> map, boolean nested) {
        if (propertyKey != null) {
            final ElasticAttribute elasticAttribute = new ElasticAttribute(propertyKey);
            final Class<?> binding;
            switch (propertyType) {
            case "geo_point":
                binding = Point.class;
                elasticAttribute.setSrid(4326);
                elasticAttribute.setGeometryType(ElasticGeometryType.GEO_POINT);
                break;
            case "geo_shape":
                binding = Geometry.class;
                elasticAttribute.setSrid(4326);
                elasticAttribute.setGeometryType(ElasticGeometryType.GEO_SHAPE);
                break;
            case "string":
                binding = String.class;
                final String index = (String) map.get("index");
                final boolean analyzed = index == null || index.equals("analyzed");
                elasticAttribute.setAnalyzed(analyzed);
                break;
            case "integer":
                binding = Integer.class;
                break;
            case "long":
                binding = Long.class;
                break;
            case "float":
                binding = Float.class;
                break;
            case "double":
                binding = Double.class;
                break;
            case "boolean":
                binding = Boolean.class;
                break;
            case "date":
                String format = (String) map.get("format");
                if (format != null) {
                    try {
                        Joda.forPattern(format);
                    } catch (Exception e) {
                        LOGGER.fine("Unable to parse date format ('" + format + "') for " + propertyKey);
                        format = null;
                    }
                }
                if (format == null) {
                    format = "date_optional_time";
                }
                elasticAttribute.setDateFormat(format);
                binding = Date.class;
                break;
            default:
                binding = null;
                break;
            }
            if (binding != null) {
                final boolean stored;
                if (map.get("store") != null) {
                    stored = (Boolean) map.get("store");
                } else {
                    stored = false;
                }
                elasticAttribute.setStored(stored);
                elasticAttribute.setType(binding);
                elasticAttribute.setNested(nested);
                elasticAttributes.add(elasticAttribute);
            }
        }
    }
}

package org.fao.geonet.geocat.kernel.reusable;

import com.google.common.base.Function;
import com.google.common.collect.Sets;
import jeeves.server.UserSession;
import jeeves.xlink.XLink;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldCollector;
import org.fao.geonet.constants.Params;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.MetadataDataInfo;
import org.fao.geonet.domain.MetadataDataInfo_;
import org.fao.geonet.domain.MetadataSourceInfo;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.domain.Metadata_;
import org.fao.geonet.domain.OperationAllowedId_;
import org.fao.geonet.domain.Pair;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.search.IndexAndTaxonomy;
import org.fao.geonet.kernel.search.SearchManager;
import org.fao.geonet.kernel.search.index.GeonetworkMultiReader;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.languages.IsoLanguagesMapper;
import org.fao.geonet.repository.MetadataRepository;
import org.fao.geonet.repository.OperationAllowedRepository;
import org.fao.geonet.repository.SettingRepository;
import org.fao.geonet.repository.specification.MetadataSpecs;
import org.fao.geonet.repository.statistic.PathSpec;
import org.fao.geonet.schema.iso19139che.ISO19139cheSchemaPlugin;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.domain.Specifications;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Root;

import static org.springframework.data.jpa.domain.Specifications.where;

/**
 * Strategies for shared objects that are subtemplates.
 *
 * @author Jesse on 10/27/2014.
 */
public abstract class AbstractSubtemplateStrategy extends ReplacementStrategy {
    protected final SearchManager searchManager;
    protected final IsoLanguagesMapper mapper;
    protected final MetadataRepository metadataRepository;
    protected final SettingRepository settingRepository;
    protected final DataManager dataManager;
    protected final OperationAllowedRepository operationAllowedRepository;

    public AbstractSubtemplateStrategy(ApplicationContext context) {
        this.metadataRepository = context.getBean(MetadataRepository.class);
        this.operationAllowedRepository = context.getBean(OperationAllowedRepository.class);
        this.searchManager = context.getBean(SearchManager.class);
        this.settingRepository = context.getBean(SettingRepository.class);
        this.dataManager = context.getBean(DataManager.class);
        this.mapper = context.getBean(IsoLanguagesMapper.class);
    }


    public final Pair<Collection<Element>, Boolean> find(Element placeholder, Element originalElem, String twoCharLangCode)
            throws Exception {
        if (XLink.isXLink(originalElem)) {
            return NULL;
        }

        String threeCharLangCode = mapper.iso639_1_to_iso639_2(twoCharLangCode);
        Query query = createSearchQuery(originalElem, twoCharLangCode, threeCharLangCode);

        final IndexAndTaxonomy indexAndTaxonomy = this.searchManager.getNewIndexReader(threeCharLangCode);
        try {

            TopFieldCollector collector = TopFieldCollector.create(Sort.RELEVANCE, 30000, true, false, false, false);

            final GeonetworkMultiReader reader = indexAndTaxonomy.indexReader;
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.search(query, collector);

            ScoreDoc[] topDocs = collector.topDocs().scoreDocs;

            Document bestFit = null;
            int rating = 0;
            for (ScoreDoc scoreDoc : topDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                final FindResult findResult = calculateFit(originalElem, doc, twoCharLangCode, threeCharLangCode);
                if (findResult.rating > rating || findResult.perfectMatch) {
                    bestFit = doc;
                    rating = findResult.rating;

                    if (findResult.perfectMatch) {
                        break;
                    }
                }
            }

            if (bestFit == null) {
                return NULL;
            } else {

                String uuid = bestFit.get(LUCENE_UUID_FIELD);
                boolean validated = LUCENE_EXTRA_VALIDATED.equals(bestFit.get(LUCENE_EXTRA_FIELD));
                Collection<Element> xlinkIt = xlinkIt(originalElem, uuid, validated);

                return Pair.read(xlinkIt, true);
            }
        } finally {
            this.searchManager.releaseIndexReader(indexAndTaxonomy);
        }
    }

    public final void performDelete(String[] uuids, UserSession session, String ignored) throws Exception {
        final Specification<Metadata> spec = where(MetadataSpecs.hasMetadataUuidIn(Arrays.asList(uuids)));
        final List<Integer> idsBy = this.metadataRepository.findAllIdsBy(spec);
        for (Integer id : idsBy) {
            this.operationAllowedRepository.deleteAllByIdAttribute(OperationAllowedId_.metadataId, id);
        }

        this.metadataRepository.deleteAll(spec);
    }

    public final Map<String, String> markAsValidated(String[] uuids, UserSession session) throws Exception {

        final Specification<Metadata> spec = where(MetadataSpecs.hasMetadataUuidIn(Arrays.asList(uuids)));

        this.metadataRepository.createBatchUpdateQuery(new PathSpec<Metadata, String>() {
            @Override
            public Path<String> getPath(Root<Metadata> root) {
                return root.get(Metadata_.dataInfo).get(MetadataDataInfo_.extra);
            }
        }, LUCENE_EXTRA_VALIDATED, spec).execute();

        Map<String, String> idMap = new HashMap<String, String>();

        for (String id : uuids) {
            idMap.put(id, id);
        }
        return idMap;
    }

    public final boolean isValidated(String href) throws NumberFormatException, SQLException {
        String uuid = Utils.id(href);
        if (uuid == null) return false;
        try {
            Specifications<Metadata> spec = where(MetadataSpecs.hasMetadataUuid(uuid))
                    .and(MetadataSpecs.hasExtra(LUCENE_EXTRA_VALIDATED));
            return this.metadataRepository.count(spec) == 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public final String createAsNeeded(String href, UserSession session) throws Exception {
        String startId = Utils.uuid(href);
        if (startId != null && !startId.isEmpty()) return href;

        String extraData = createExtraData(href);

        String template = getEmptyTemplate();
        String uuid = UUID.randomUUID().toString();
        Metadata metadata = new Metadata().setData(template);
        addBasicMetadataInfo(uuid, false, "che:CHE_CI_ResponsibleParty", metadata);
        this.metadataRepository.save(metadata);

        return createXlinkHref(uuid, session, extraData);
    }

    protected abstract String createExtraData(String href);

    protected final void addBasicMetadataInfo(String uuid, boolean validated, String root, Metadata metadata) {
        metadata.setUuid(uuid);
        MetadataDataInfo dataInfo = new MetadataDataInfo().
                setExtra(validated ? LUCENE_EXTRA_VALIDATED : LUCENE_EXTRA_NON_VALIDATED).
                setRoot(root).
                setSchemaId(ISO19139cheSchemaPlugin.IDENTIFIER).
                setType(MetadataType.SUB_TEMPLATE);
        metadata.setDataInfo(dataInfo);
        metadata.setSourceInfo(new MetadataSourceInfo().setSourceId(getSourceId()));
    }

    public final Collection<Element> add(Element placeholder, Element originalElem, String metadataLang)
            throws Exception {
        UpdateResult result = updateSubtemplate(originalElem, null, false, metadataLang);

        return xlinkIt(originalElem, result.uuid, false);
    }


    public final String updateHrefId(String oldHref, String id, UserSession session) {
        return oldHref.replaceAll("uuid=[^&#]+", "uuid=" + id).replaceAll("/fra/|/deu/|/ita/|/___/", "/eng/");
    }

    public final Collection<Element> updateObject(Element xlink, String metadataLang) throws Exception {
        String uuid = Utils.extractUrlParam(xlink, Params.UUID);

        updateSubtemplate(xlink, uuid, false, metadataLang);

        xlinkIt(xlink, uuid, false);
        return Collections.emptySet();
    }

    protected static class UpdateResult {
        String uuid;
    }

    /**
     * Executes the query using all the user data from the element. There must
     * be exactly 28 ? in the query. id is not one of them
     *
     * @param metadataLang
     */
    protected final UpdateResult updateSubtemplate(Element originalElem, String uuid, boolean validated,
                                             String metadataLang) throws Exception {
        Element responsibleParty = getSubtemplate(originalElem, metadataLang);

        Metadata metadata = null;
        if (uuid != null) {
            metadata = this.metadataRepository.findOneByUuid(uuid);
        }
        if (metadata == null) {
            metadata = new Metadata();
            uuid = UUID.randomUUID().toString();
        }
        metadata.setDataAndFixCR(responsibleParty);
        addBasicMetadataInfo(uuid, validated, responsibleParty.getQualifiedName(), metadata);

        this.metadataRepository.save(metadata);

        this.dataManager.indexMetadata(String.valueOf(metadata.getId()), true, false, false, false);

        UpdateResult result = new UpdateResult();
        result.uuid = uuid;

        return result;
    }

    protected abstract Element getSubtemplate(Element originalElem, String metadataLang) throws Exception;

    protected abstract String getEmptyTemplate();
    protected abstract Collection<Element> xlinkIt(Element originalElem, String uuid, boolean validated);
    protected abstract FindResult calculateFit(Element originalElement, Document doc, String twoCharLangCode, String threeCharLangCode) throws JDOMException;
    protected abstract Query createSearchQuery(Element originalElem, String twoCharLangCode, String threeCharLangCode) throws
            JDOMException;

    protected static final class FindResult {
        final boolean perfectMatch;
        final int rating;

        public FindResult(boolean perfectMatch, int rating) {
            this.perfectMatch = perfectMatch;
            this.rating = rating;
        }
    }
    public final static class DescData {
        public final String uuid;
        public final Document doc;

        private DescData(String uuid, Document doc) {
            this.uuid = uuid;
            this.doc = doc;
        }
    }
    protected final Element listFromIndex(SearchManager searchManager, String root, boolean validated, String language,
                                          UserSession session,
                                          ReplacementStrategy strategy,
                                          Function<DescData, String> describer) throws Exception {

        final IndexAndTaxonomy newIndexReader = searchManager.getNewIndexReader(language);
        Element results = new Element(REPORT_ROOT);
        try {
            final GeonetworkMultiReader reader = newIndexReader.indexReader;
            IndexSearcher searcher = new IndexSearcher(reader);
            final BooleanQuery booleanQuery = new BooleanQuery();
            final String contactType = validated ? LUCENE_EXTRA_VALIDATED : LUCENE_EXTRA_NON_VALIDATED;
            booleanQuery.add(new TermQuery(new Term(LUCENE_EXTRA_FIELD, contactType)), BooleanClause.Occur.MUST);
            booleanQuery.add(new TermQuery(new Term(LUCENE_ROOT_FIELD, root)), BooleanClause.Occur.MUST);
            booleanQuery.add(new TermQuery(new Term(LUCENE_LOCALE_FIELD, language)), BooleanClause.Occur.SHOULD);
            booleanQuery.add(new TermQuery(new Term(LUCENE_SCHEMA_FIELD, ISO19139cheSchemaPlugin.IDENTIFIER)), BooleanClause.Occur.MUST);
            TopFieldCollector collector = TopFieldCollector.create(
                    Sort.INDEXORDER, 30000, true, false, false, false);
            searcher.search(booleanQuery, collector);

            ScoreDoc[] topDocs = collector.topDocs().scoreDocs;
            Set<String> added = Sets.newHashSet();
            for (ScoreDoc topDoc : topDocs) {

                final Document doc = searcher.doc(topDoc.doc);
                String uuid = doc.getField("_uuid").stringValue();

                if (added.contains(uuid)) {
                    continue;
                }
                added.add(uuid);

                Element e = new Element(REPORT_ELEMENT);
                String id = doc.get("_id");
                String url = XLink.LOCAL_PROTOCOL + "catalog.edit#/metadata/" + id;
                String desc = describer.apply(new DescData(uuid, doc));
                Utils.addChild(e, REPORT_URL, url);
                Utils.addChild(e, REPORT_ID, uuid);
                Utils.addChild(e, REPORT_TYPE, "contact");
                Utils.addChild(e, REPORT_XLINK, strategy.createXlinkHref(uuid, session, ""));
                Utils.addChild(e, REPORT_DESC, desc);
                Utils.addChild(e, REPORT_SEARCH, uuid + desc);

                results.addContent(e);
            }

        } finally {
            searchManager.releaseIndexReader(newIndexReader);
        }

        return results;
    }
    private final String getSourceId() {
        return this.settingRepository.findOne(SettingManager.SYSTEM_SITE_SITE_ID_PATH).getValue();
    }

}
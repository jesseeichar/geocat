//=============================================================================
//===	Copyright (C) 2001-2007 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================

package org.fao.geonet.geocat.cgp;

import jeeves.server.context.ServiceContext;
import org.fao.geonet.Logger;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.exceptions.NoSchemaMatchesException;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.harvest.harvester.CategoryMapper;
import org.fao.geonet.kernel.harvest.harvester.GroupMapper;
import org.fao.geonet.kernel.harvest.harvester.HarvestResult;
import org.fao.geonet.kernel.harvest.harvester.Privileges;
import org.fao.geonet.kernel.harvest.harvester.UUIDMapper;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.lib.Lib;
import org.fao.geonet.repository.MetadataRepository;
import org.fao.geonet.util.PasswordUtil;
import org.fao.geonet.utils.Log;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;

import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


//=============================================================================

/**
 * Harvest metadata from CGP SOAP servers.
 * <p/>
 * <p/>
 * Metadata produced are :
 * <ul>
 * <li>ISO19119 for service's metadata</li>
 * <li>ISO19139 for data's metadata</li>
 * </ul>
 *
 * @author justb
 */
class Harvester {
    private final AtomicBoolean cancelMonitor;
    DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    DateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    /**
     * Constructor.
     *
     * @param cancelMonitor
     * @param log     logger
     * @param context Jeeves context
     * @param params  Information about harvesting configuration for the node
     */
    public Harvester(AtomicBoolean cancelMonitor, Logger log, ServiceContext context, CGPParams params) {
        this.cancelMonitor = cancelMonitor;
        this.log = log;
        this.context = context;
        this.params = params;

        result = new HarvestResult();

        dataMan = context.getBean(DataManager.class);
    }

    //---------------------------------------------------------------------------
    //---
    //--- API methods
    //---
    //---------------------------------------------------------------------------

    /**
     * Start the harvesting of a CGP server.
     */
    public HarvestResult harvest() throws Exception {
        log.info("Retrieving remote CGP metadata information for: " + params.getName());

        // Clean all before harvest : Remove/Add mechanism
        // If harvest failed (ie. if node unreachable), metadata will be removed, and
        // the node will not be referenced in the catalogue until next harvesting.
        // TODO : define a rule for UUID in order to be able to do an update operation ?
        final MetadataRepository metadataRepository = context.getBean(MetadataRepository.class);
        localUuids = new UUIDMapper(metadataRepository, params.getUuid());

        // Try to load capabilities document
        CGPRequest cgpRequest = new CGPRequest(context, params.url);

        // Optional proxy
        setupProxy(context, cgpRequest);

        // Query records by wildcard
        List<Element> recordElms;
        CGPQueryCriteria cgpQueryCriteria = new CGPQueryCriteria();

        // Setup Query criteria based on search parms
        if (params.isSearchEmpty()) {
            // Default: get all using "beforeDate" in far distant future
            cgpQueryCriteria.addBeforeDistantDateExpression();
        } else {
            // Only one search element is supported for now
            Search search = params.getSearches().iterator().next();
            String criteriaSummary = "";

            // Freetext search
            if (search.freeText != null) {
                criteriaSummary += "freeText=" + search.freeText;
                cgpQueryCriteria.addFreeTextExpression(search.freeText);
            }

            // From date search
            if (search.from != null) {
                criteriaSummary += " from=" + search.from;
                cgpQueryCriteria.addAfterDateExpression(search.from);
            }

            // Until date search
            if (search.until != null) {
                criteriaSummary += " until=" + search.until;
                cgpQueryCriteria.addBeforeDateExpression(search.until);
            }

            // Bounding Box (BBox) search
            if (search.hasBBox()) {
                criteriaSummary += " bbox=" + search.getBBoxStr();
                cgpQueryCriteria.addBBoxExpression(search.lonWest, search.latSouth, search.lonEast, search.latNorth);
            }

            log.info("CGP Harvester, query criteria : " + criteriaSummary);
        }

        // Do the remote CGP Query
        recordElms = cgpRequest.query(cgpQueryCriteria);
        log.info("CGP Harvester, got : " + recordElms.size() + " records");
        result.totalMetadata = recordElms.size();

        // Remove old if query succeeded
        removeOldMetadata();

        // Load categories and groups
        localCateg = new CategoryMapper(context);
        localGroups = new GroupMapper(context);

        // Fetches each record and adds it.
        String objectId;
        for (Element recordElm : recordElms) {
            if (cancelMonitor.get()) {
                break;
            }

            objectId = recordElm.getAttributeValue("objid");

            log.info("CGP Harvester, addMetadata objid: " + objectId);

            // Envirocat does not support presentation request.
            if (params.getName().toLowerCase().equals("envirocat")) {
                log.info("CGP Harvester, envirocat node does not support presentation request, using small to add the record.");
                addMetadata(objectId, recordElm, true);
            } else {

                // Add single MD record
                addMetadata(objectId, cgpRequest.getEntry(objectId), false);

                log.info("CGP add, OK added record " + result.addedMetadata + " of " + recordElms.size());
            }
        }

        return result;
    }


    /**
     * Add metadata to the node for a GM03 Element.
     * <p/>
     * Envirocat is only returning small response and does not
     * support Presentation request so transform small
     * to ISO19139.che.
     *
     * @param anObjectId the Element object Id.
     * @param gm03Elm    GM03Comprehensive (TRANSFER) Element or GM03Small element if envirocat
     * @param envirocat  harvesting envirocat catalogue
     */
    private void addMetadata(String anObjectId, Element gm03Elm, boolean envirocat) throws Exception {
        if (gm03Elm == null) {
            return;
        }

        // Uuid from unique URL+objId
        String uuid = PasswordUtil.encode(context, params.url + anObjectId);

        // ie. detect GM03;
        Element md;
        try {
            dataMan.autodetectSchema(gm03Elm);
            md = gm03Elm;
        } catch (NoSchemaMatchesException e) {
            // see if it is a GM03 variant

            // Loading stylesheet

            Path styleSheet = context.getAppPath().resolve(Geonet.Path.IMPORT_STYLESHEETS).resolve(
                    (envirocat ? "GM03SMALL-to-ISO19139CHE.xsl" : "GM03-to-ISO19139CHE.xsl"));

            // log.info("  - XSLT transformation using /GM03-to-ISO19139CHE.xsl");

            Map<String, Object> param = new HashMap<String, Object>();
            //param.put("lang", params.lang);
            //param.put("topic", params.topic);
            param.put("uuid", uuid);

            // Hack: transform fails with namespace, so remove all for now.
            if (!envirocat)
                removeNamespaces(gm03Elm);

            // String transferElmStr = Xml.getString(gm03Elm);

            // Transform into GN native MD format (iso19139)
            md = Xml.transform(gm03Elm, styleSheet, param);
        }


        // ie. detect iso19139;
        String schema = dataMan.autodetectSchema(md);

        if (schema == null) {
            log.warning("Skipping transformed metadata with unknown schema.");
            result.unknownSchema++;
            return;
        }

        // String mdStr = Xml.getString(md);
        // Validate if specified
        try {
            params.getValidate().validate(dataMan, context, md);
        } catch (Exception e) {
            log.info("Ignoring invalid metadata with uuid " + uuid);
            result.doesNotValidate++;
            return;
        }

        String id;
        try {
            // Save iso19139 metadata in DB
            // log.info("  - Adding metadata for services with " + uuid);
            Date date = getMetadataDate(md);

            // issue GC #133712
            uuid = org.fao.geonet.services.harvesting.Util.uuid(context, params.url, md, log, null, result, schema);

            if (uuid == null) {
                return;
            }
            // End #133712

            // Insert into DB (may raise exception)
            int userid = 1;
            String group = null, isTemplate = null, docType = null, title = null, category = null;
            boolean ufo = false, indexImmediate = false;
            String changeDate = DATE_FORMAT.format(date);
            id = dataMan.insertMetadata(context, schema, md, uuid, userid, group, params.getUuid(),
                    isTemplate, docType, category, changeDate, changeDate, ufo, indexImmediate);

            int iId = Integer.parseInt(id);
            dataMan.setTemplateExt(iId, MetadataType.METADATA);
            dataMan.setHarvestedExt(iId, params.getUuid());

            addPrivileges(id);
            addCategories(id);
        } catch (Throwable t) {
            // This sometimes occurs...
            log.warning("DB insert error: " + t + " msg=" + t.getMessage() + "  Remote objectid : " + anObjectId + " md=" + Xml
                    .getString(md));

            result.couldNotInsert++;
            return;
        }

        // ASSERT: insert committed DB ok

        // Add to Lucene
        try {
            dataMan.indexMetadata(id, false);
            // Add Thumbnails ??
            result.addedMetadata++;
        } catch (Throwable t) {
            // This sometimes occurs...
            log.warning("MD indexing error: " + t + " msg=" + t.getMessage() + "  Remote objectid : " + anObjectId);
            result.couldNotInsert++;
            return;
        }
    }

    /**
     * Recursively remove namespaces from Element.
     */
    private void removeNamespaces(Element rootElm) throws Exception {
        rootElm.setNamespace(null);
        @SuppressWarnings("unchecked")
        List<Element> childElms = rootElm.getChildren();
        for (Element elm : childElms) {
            removeNamespaces(elm);
        }
    }

    private int removeOldMetadata() throws Exception {
        // Clean all before harvest : Remove/Add mechanism
        localUuids = new UUIDMapper(context.getBean(MetadataRepository.class), params.getUuid());

        // -----------------------------------------------------------------------
        // --- remove old metadata
        for (String uuid : localUuids.getUUIDs()) {
            String id = localUuids.getID(uuid);

            log.debug("  - Removing old metadata before update with id: " + id);

            // Remove thumbnails ??
            // TODO : unsetThumbnail(id);

            // Remove metadata
            dataMan.deleteMetadata(context, id);

            result.locallyRemoved++;
        }

        return result.locallyRemoved;
    }

    /**
     * Add categories according to harvesting configuration
     *
     * @param id GeoNetwork internal identifier
     */
    private void addCategories(String id) throws Exception {
        for (String catId : params.getCategories()) {
            String name = localCateg.getName(catId);

            if (name == null) {
                log.debug("    - Skipping removed category with id:" + catId);
            } else {
                dataMan.setCategory(context, id, catId);
            }
        }
    }


    /**
     * Add privileges according to harvesting configuration
     *
     * @param id GeoNetwork internal identifier
     */
    private void addPrivileges(String id) throws Exception {
        for (Privileges priv : params.getPrivileges()) {
            String name = localGroups.getName(priv.getGroupId());

            if (name == null) {
                log.debug("    - Skipping removed group with id:" + priv.getGroupId());
            } else {
                final int mdId = Integer.parseInt(id);
                for (int opId : priv.getOperations()) {
                    name = dataMan.getAccessManager().getPrivilegeName(opId);

                    //--- allow only: view, dynamic, featured
                    if (opId == 0 || opId == 5 || opId == 6) {
                        dataMan.setOperation(context, mdId, Integer.parseInt(priv.getGroupId()), opId);
                    } else {
                        log.debug("       --> " + name + " (skipped)");
                    }
                }
            }
        }
    }

    //-----------------------------------------------------------------------------

    /**
     * Setup proxy
     */

    public void setupProxy(ServiceContext context, SOAPRequest req) {
        SettingManager sm = context.getBean(SettingManager.class);

        boolean enabled = sm.getValueAsBool("system/proxy/use", false);

        if (!enabled) {
            req.setUseProxy(false);
        } else {
            String host = sm.getValue("system/proxy/host");
            String port = sm.getValue("system/proxy/port");
            String username = sm.getValue("system/proxy/username");
            String password = sm.getValue("system/proxy/password");

            if (!Lib.type.isInteger(port)) {
                Log.error(Geonet.GEONETWORK, "Proxy port is not an integer : " + port);
            } else {
                req.setUseProxy(true);
                req.setProxyHost(host);
                req.setProxyPort(Integer.parseInt(port));
                req.setProxyCredentials(username, password);
            }
        }
    }

    //--------------------------------------------------------------------------

    private boolean validates(String schema, Element md) {
        try {
            log.debug("Validating for " + schema);
            dataMan.validate(schema, md);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets the dateStamp value in metadata
     * <p/>
     * If value not present gets actual date
     *
     * @param md metadata record
     * @return dateStamp value in metadata, if present (else actual date)
     */
    private Date getMetadataDate(Element md) {
        Date date = new Date();

        try {
            Element ds = md.getChild("dateStamp", md.getNamespace(""));

            if (ds != null) {
                Element dt = ds.getChild("DateTime", md.getNamespace("gco"));
                if (dt != null) {
                    date = DATE_FORMAT.parse(dt.getValue());

                } else {
                    Element d = ds.getChild("Date", md.getNamespace("gco"));
                    if (d != null) {
                        date = SIMPLE_DATE_FORMAT.parse(d.getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.error("  - getMetadataDate (error):" + e.getMessage());
            e.printStackTrace();
        }

        return date;
    }
    //---------------------------------------------------------------------------
    //---
    //--- Variables
    //---
    //---------------------------------------------------------------------------

    private final Logger log;
    private final ServiceContext context;
    private final CGPParams params;
    private final DataManager dataMan;
    private CategoryMapper localCateg;
    private GroupMapper localGroups;
    private final HarvestResult result;
    private UUIDMapper localUuids;
}


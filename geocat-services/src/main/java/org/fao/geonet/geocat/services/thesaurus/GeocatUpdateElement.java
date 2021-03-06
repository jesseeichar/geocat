//=============================================================================
//===	Copyright (C) 2001-2005 Food and Agriculture Organization of the
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
//===	Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: GeoNetwork@fao.org
//==============================================================================

package org.fao.geonet.geocat.services.thesaurus;

import jeeves.constants.Jeeves;
import jeeves.interfaces.Service;
import jeeves.server.ServiceConfig;
import jeeves.server.context.ServiceContext;
import org.fao.geonet.Constants;
import org.fao.geonet.Util;
import org.fao.geonet.constants.Params;
import org.fao.geonet.geocat.kernel.reusable.KeywordsStrategy;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.KeywordBean;
import org.fao.geonet.kernel.Thesaurus;
import org.fao.geonet.kernel.ThesaurusManager;
import org.fao.geonet.kernel.UpdateReferencedMetadata;
import org.fao.geonet.languages.IsoLanguagesMapper;
import org.fao.geonet.util.ThreadPool;
import org.jdom.Element;

import java.net.URLEncoder;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

//=============================================================================

/**
 * Update the information of a thesaurus
 */

public class GeocatUpdateElement implements Service {
    public void init(Path appPath, ServiceConfig params) throws Exception {
    }

    // --------------------------------------------------------------------------
    // ---
    // --- Service
    // ---
    // --------------------------------------------------------------------------

    /*
     * TODO
     */
    public Element exec(Element params, ServiceContext context)
            throws Exception {

        String ref = Util.getParam(params, Params.REF);
        String oldid = Util.getParam(params, "oldid", null);
        String newid = Util.getParam(params, "newid", null);
        String namespace = Util.getParam(params, "namespace");
        String thesaType = Util.getParam(params, "refType");
        Map<String, String> prefLab = lookupLabels(params);
        String definition = Util.getParam(params, "definition", "");

        ThesaurusManager manager = context.getBean(ThesaurusManager.class);
        Thesaurus thesaurus = manager.getThesaurusByName(ref);

        if (oldid == null) {
            newid = UUID.randomUUID().toString();
        } else if (!(oldid.equals(newid))) {
            if (thesaurus.isFreeCode(namespace, newid)) {
                thesaurus.updateCode(namespace, oldid, newid);
            } else {
                Element elResp = new Element(Jeeves.Elem.RESPONSE);
                elResp.addContent(new Element("error").addContent(new Element("message").setText("Code value already exists in " +
                                                                                                 "thesaurus")));
                return elResp;
            }
        }
        KeywordBean bean = new KeywordBean(context.getBean(IsoLanguagesMapper.class)).setNamespaceCode(namespace).setRelativeCode(newid);
        if (thesaType.equals("place")) {
            String east = Util.getParam(params, "east");
            String west = Util.getParam(params, "west");
            String south = Util.getParam(params, "south");
            String north = Util.getParam(params, "north");
            bean.setCoordEast(east)
                    .setCoordNorth(north)
                    .setCoordSouth(south)
                    .setCoordWest(west);
        }

        for (Map.Entry<String, String> entry : prefLab.entrySet()) {
            bean.setValue(entry.getValue(), entry.getKey());
            bean.setDefinition(definition, entry.getKey());
        }
        String uri;
        if (oldid != null) {
            uri = thesaurus.updateElement(bean, true).getURI();
        } else {
            uri = thesaurus.addElement(bean).getURI();
        }

        final KeywordsStrategy strategy = new KeywordsStrategy(context.getBean(IsoLanguagesMapper.class), manager, context.getAppPath(), context.getBaseUrl(), context.getLanguage());
        context.getBean(ThreadPool.class).runTask(new UpdateReferencedMetadata(URLEncoder.encode(uri, Constants.ENCODING), context.getBean(DataManager.class), strategy));

        Element elResp = new Element(Jeeves.Elem.RESPONSE);

        elResp.addContent(new Element("selected").setText(ref));
        elResp.addContent(new Element("mode").setText("edit"));
        elResp.addContent(new Element("id").setText(uri));
        return elResp;
    }

    static Map<String, String> lookupLabels(Element params) {
        final String prefix = "loc_";

        HashMap<String, String> mappings = new HashMap<String, String>();
        for (Element e : (Collection<Element>) params.getChildren()) {
            if (e.getName().startsWith(prefix)) {
                String language = e.getName().substring(prefix.length()).toLowerCase();
                language = language.substring(0, language.indexOf("_"));
                mappings.put(language, e.getTextTrim());
            }
        }

        return mappings;
    }
}

// =============================================================================


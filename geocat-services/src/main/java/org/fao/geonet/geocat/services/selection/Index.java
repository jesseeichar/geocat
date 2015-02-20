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

package org.fao.geonet.geocat.services.selection;

import com.google.common.collect.Sets;
import jeeves.interfaces.Service;
import jeeves.server.ServiceConfig;
import jeeves.server.context.ServiceContext;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.SelectionManager;
import org.fao.geonet.services.metadata.BatchOpsMetadataReindexer;
import org.jdom.Element;

import java.nio.file.Path;
import java.util.Set;

//=============================================================================

/**
 * Force rebuild Lucene index
 */

public class Index implements Service {
    private Integer maxToIndex = 500;

    //--------------------------------------------------------------------------
    //---
    //--- Init
    //---
    //--------------------------------------------------------------------------

    public void init(Path appPath, ServiceConfig config) throws Exception {
        this.maxToIndex = Integer.valueOf(config.getValue("maxToIndex"));
    }

    //--------------------------------------------------------------------------
    //---
    //--- Service
    //---
    //--------------------------------------------------------------------------

    public Element exec(Element params, final ServiceContext context) throws Exception {
        final SelectionManager manager = SelectionManager.getManager(context.getUserSession());
        final Set<String> selection = manager.getSelection(SelectionManager.SELECTION_METADATA);
        int index = 0;
        if (selection != null) {
            synchronized (selection) {
                if (!selection.isEmpty()) {
                    if (false && selection.size() > maxToIndex) {
                        return new Element("error").setText("Attempted to index " + selection.size() + ".  The maximum allowed elements: " +

                                                            maxToIndex);
                    }
                    final DataManager dataManager = context.getBean(DataManager.class);
                    Set<Integer> ids = Sets.newHashSet();

                    for (String uuid : selection) {
                        try {
                            final String metadataId = dataManager.getMetadataId(uuid);
                            if (metadataId != null) {
                                ids.add(Integer.valueOf(metadataId));
                            }
                        } catch (Exception e) {
                            try {
                                ids.add(Integer.valueOf(uuid));
                            } catch (NumberFormatException nfe) {
                                // skip
                            }
                        }
                    }

                    index = ids.size();
                    new BatchOpsMetadataReindexer(dataManager, ids).process();
                }
            }
        }

        return new Element("results").setAttribute("numberIndexed", "" + index);
    }
}

//=============================================================================



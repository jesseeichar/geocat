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

package org.fao.geonet.services.user;

import jeeves.constants.Jeeves;
import jeeves.interfaces.Service;
import jeeves.server.ServiceConfig;
import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import org.fao.geonet.constants.Geocat;
import org.fao.geonet.domain.User;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.constants.Params;
import org.fao.geonet.domain.Profile;
import org.fao.geonet.domain.UserGroup;
import org.fao.geonet.repository.UserGroupRepository;
import org.fao.geonet.repository.UserRepository;
import org.fao.geonet.util.LangUtils;
import org.jdom.JDOMException;

import java.io.IOException;
import java.sql.SQLException;
import org.jdom.Element;

import java.util.List;

import static org.fao.geonet.repository.specification.UserGroupSpecs.hasUserId;
import static org.springframework.data.jpa.domain.Specifications.*;

//=============================================================================

/**
 * Retrieves a particular user
 */

public class Get implements Service {
    //--------------------------------------------------------------------------
    //---
    //--- Init
    //---
    //--------------------------------------------------------------------------

    public void init(String appPath, ServiceConfig params) throws Exception {
    }

    //--------------------------------------------------------------------------
    //---
    //--- Service
    //---
    //--------------------------------------------------------------------------

    public Element exec(Element params, ServiceContext context) throws Exception {
        String id = params.getChildText(Params.ID);

        UserSession usrSess = context.getUserSession();
        if (!usrSess.isAuthenticated()) return new Element(Jeeves.Elem.RESPONSE);

        Profile myProfile = usrSess.getProfile();
        String myUserId = usrSess.getUserId();

        if (id == null) return new Element(Jeeves.Elem.RESPONSE);

        final User user = context.getBean(UserRepository.class).findOne(Integer.valueOf(id));
        if (myProfile == Profile.Administrator || myProfile == Profile.UserAdmin || myUserId.equals(id)) {

            Element elUser = user.asXml();
            // GEOCAT
			if(user != null && user.getProfile() == Profile.Shared) {
				return loadSharedUser(user);
			}
            // END GEOCAT

            //--- retrieve user groups

            Element elGroups = new Element(Geonet.Elem.GROUPS);

            final UserGroupRepository userGroupRepository = context.getBean(UserGroupRepository.class);
            final List<UserGroup> userGroups = userGroupRepository.findAll(hasUserId(Integer
                    .valueOf(id)));

            for (UserGroup grp : userGroups) {
                String grpId = "" + grp.getId().getGroupId();

                elGroups.addContent(new Element(Geonet.Elem.ID).setText(grpId).setAttribute("profile", grp.getProfile().name()));
            }

            if (!(myUserId.equals(id)) && myProfile == Profile.UserAdmin) {

                //--- retrieve session user groups and check to see whether this user is
                //--- allowed to get this info
                List<Integer> adminlist = userGroupRepository.findGroupIds(where(hasUserId(Integer.valueOf(myUserId))).or(hasUserId
                        (Integer.valueOf(id))));
                if (adminlist.isEmpty()) {
                    throw new IllegalArgumentException("You don't have rights to do this because the user you want to edit is not part of your group");
                }
            }

            //--- return data

            elUser.addContent(elGroups);
            return elUser;
        } else {
            // GEOCAT
			return loadSharedUser(user);
            // END GEOCAT
        }

	}

    // GEOCAT
    private Element loadSharedUser(User user) throws SQLException, IOException, JDOMException {


        if(user.getProfile() != Profile.Shared) {
        	return new Element("record");
        }

        Element elGroups = new Element(Geonet.Elem.GROUPS);

        @SuppressWarnings("unchecked")
		java.util.List<Element> list = dbms.select("SELECT groupId FROM UserGroups WHERE userId=?", Integer.valueOf(id)).getChildren();

        for(int i=0; i<list.size(); i++)
        {
            String grpId = list.get(i).getChildText("groupid");

            elGroups.addContent(new Element(Geonet.Elem.ID).setText(grpId));
        }

        elUser.addContent(elGroups);

		//--- get the validity of the parent if it exists

		Element parentInfoId = elUser.getChild("record").getChild("parentinfo");
        if (parentInfoId.getTextTrim().length() > 0) {
            Integer integer = Integer.parseInt(parentInfoId.getTextTrim());
            Element validatedResults = dbms.select("SELECT validated FROM Users where id="+integer);

            Element elValidated = validatedResults.getChild("record").getChild("validated");

            if (elValidated != null){
                elValidated.detach();
                elValidated.setName("parentValidated");
                elUser.addContent(elValidated);
            }
		}

        //--- return data

        String[] elementsToResolve = { "organisation", "positionname", "orgacronym","onlinename","onlinedescription", "onlineresource", Geocat.Params.CONTACTINST};
        LangUtils.resolveMultiLingualElements(elUser, elementsToResolve);

        return elUser;
    }
    // END GEOCAT
}

//=============================================================================


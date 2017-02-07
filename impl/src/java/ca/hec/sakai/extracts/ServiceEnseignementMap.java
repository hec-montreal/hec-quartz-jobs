package ca.hec.sakai.extracts;

import java.util.*;

/******************************************************************************
 * $Id: $
 ******************************************************************************
 *
 * Copyright (c) 2010 The Sakai Foundation, The Sakai Quebec Team.
 *
 * Licensed under the Educational Community License, Version 1.0
 * (the "License"); you may not use this file except in compliance with the
 * License.
 * You may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/ecl1.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *****************************************************************************/

/**
 * This map contains all the SE of HEC.
 * 
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class ServiceEnseignementMap extends
	HashMap<String, ServiceEnseignementMapEntry> {

    public static final long serialVersionUID = 5386630822650707643l;

    public void put(ServiceEnseignementMapEntry entry) {
	put(entry.getAcadOrg(), entry);
    }

    public ServiceEnseignementMapEntry get(String key) {
	return (ServiceEnseignementMapEntry) super.get(key);
    }

    public void remove(ServiceEnseignementMapEntry entry) {
	remove(entry.getAcadOrg());
    }

    public Iterator<ServiceEnseignementMapEntry> getAllServices() {

	return values().iterator();
    }

    public ServiceEnseignementMapEntry getByDeptId(String deptId) {
	ServiceEnseignementMapEntry se = null;
	Iterator<ServiceEnseignementMapEntry> seIterator= this.values().iterator();
	while (seIterator.hasNext()){
	    se = seIterator.next();
	    if(deptId.equalsIgnoreCase(se.getDeptId()))
		return se;
	}
	return null;
    }

}

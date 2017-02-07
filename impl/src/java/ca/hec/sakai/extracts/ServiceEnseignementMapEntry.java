package ca.hec.sakai.extracts;

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
 * This entry represents a SE.
 * 
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class ServiceEnseignementMapEntry implements java.io.Serializable {

    public static final long serialVersionUID = -5942666321016168578l;

    private String acadOrg;
    private String descFormal;
    private String deptId;

    /**
     * Empty constructor.
     */
    public ServiceEnseignementMapEntry() {
    }

    public String getAcadOrg() {
	return acadOrg;
    }

    public void setAcadOrg(String acadOrg) {
	this.acadOrg = acadOrg;
    }

    public String getDeptId() {
	return deptId;
    }

    public void setDeptId(String deptId) {
	this.deptId = deptId;
    }

    /**
     * @return the descFormal
     */
    public String getDescFormal() {
	return descFormal;
    }

    /**
     * @param descFormal the descFormal to set
     */
    public void setDescFormal(String descFormal) {
	this.descFormal = descFormal;
    }

    public boolean equals(Object o) {
	if (o == null) {
	    return false;
	} else if (!o.getClass().getName().equals(
		"ca.hec.peoplesoft.ServiceEnseignementMapEntry")) {
	    return false;
	} else {
	    if (o.hashCode() == hashCode()) {
		return true;
	    } else {
		return false;
	    }
	}
    } // equals
}

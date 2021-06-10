/******************************************************************************
 * $Id: $
 ******************************************************************************
 *
 * Copyright (c) 2021 The Sakai Foundation, The Sakai Quebec Team.
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
 ******************************************************************************/
package ca.hec.jobs.impl.site;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.hsqldb.rights.User;
import org.quartz.JobExecutionContext;
import lombok.Setter;

import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.authz.api.Member;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.UserAlreadyDefinedException;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserEdit;
import org.sakaiproject.user.api.UserLockedException;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.sakaiproject.user.api.UserPermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.hec.jobs.api.site.HecEdStudentSynchroJob;
import lombok.Setter;

/**
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class HecEdStudentSynchroJobImpl implements HecEdStudentSynchroJob {

    @Setter
    private AuthzGroupService authzGroupService;

    @Setter
    protected UserDirectoryService userDirectoryService;
    @Setter
    protected SiteService siteService;
    @Setter
    protected SessionManager sessionManager;
    @Setter
    protected ServerConfigurationService serverConfigService;

    private static Logger log =
	    LoggerFactory.getLogger(HecEdStudentSynchroJobImpl.class);

    private static final String EMAIL_PATTERN = "^(.+)@(\\S+)$";

    private static final String SITE_REFERENCE_PREFIX = "/site/";

    private static final Pattern pattern = Pattern.compile(EMAIL_PATTERN);

    @Override
    public void execute(JobExecutionContext context)
	    throws JobExecutionException {
	Session session = sessionManager.getCurrentSession();
	String siteIds = context.getMergedJobDataMap().getString("sites");
	String[] sitesList = null;
	// Site site = null;
	AuthzGroup authzGroup = null;
	Matcher matcher = null;
	UserEdit userEdit = null;
	String userSiteId = null;
	Site userSite = null;

	if (StringUtils.isNotBlank(siteIds)) {
	    sitesList = siteIds.split(",");
	}

	session.setUserEid("admin");
	session.setUserId("admin");

	for (String siteId : sitesList) {
	    try {
		authzGroup = authzGroupService
			.getAuthzGroup(SITE_REFERENCE_PREFIX + siteId);

		for (Member member : authzGroup.getMembers()) {
		    // Do not change provided members
		    if (!member.isProvided()) {
			matcher = pattern.matcher(member.getUserEid());
			try {
			    if (matcher.matches()) {
				userEdit = userDirectoryService
					.editUser(member.getUserId());
				if (!GUESTED_USER_TYPE
					.equalsIgnoreCase(userEdit.getType())) {
				    userEdit.setType(GUESTED_USER_TYPE);
				    userDirectoryService.commitEdit(userEdit);

				    // Delete workspace if it exists
				    userSiteId = siteService
					    .getUserSiteId(member.getUserId());
				    userSite = siteService.getSite(userSiteId);
				    siteService.removeSite(userSite);
				}
			    }
			} catch (IdUnusedException | PermissionException e5) {
			    log.error("L'espace personnel de "
				    + member.getUserId()
				    + " ne peut pas être modifié "
				    + "parce qu'il n'existe pas ou vous n'avez pas le droit de le faire.");
			} catch (UserNotDefinedException e1) {
			    log.error("L'utilisateur " + member.getUserId()
				    + " n'existe pas");
			} catch (UserPermissionException e2) {
			    log.error(
				    "Vous n'avez pas l'autorisation de modifier le compte de "
					    + member.getUserId());
			} catch (UserAlreadyDefinedException e3) {
			    log.error(
				    "VOus ne pouvez pas ajouter de nouveau l'utilisateur "
					    + member.getUserId());
			} catch (UserLockedException e4) {
			    log.error("Le compte utilisateur "
				    + member.getUserId() + " est bloqué");
			}

		    }
		}
	    } catch (GroupNotDefinedException e) {
		log.error("L'id du site " + siteId
			+ " n'est pas valide ou le site n'existe pas.");
	    }
	}

	session.clear();
    }

}

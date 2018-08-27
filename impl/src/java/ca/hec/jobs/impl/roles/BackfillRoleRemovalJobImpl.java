package ca.hec.jobs.impl.roles;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.AuthzPermissionException;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.javax.PagingPosition;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.site.api.SiteService.SortType;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.hec.jobs.api.roles.BackfillRoleRemovalJob;
import lombok.Setter;

public class BackfillRoleRemovalJobImpl implements BackfillRoleRemovalJob {

	@Setter
	private AuthzGroupService authzGroupService;
	
	@Setter
	private SiteService siteService;
	
    @Setter
    protected SessionManager sessionManager;
	
    private static Logger log = LoggerFactory.getLogger(BackfillRoleRemovalJobImpl.class);
	
	@Override
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
	    String roleRemovalRoleKey = jobExecutionContext.getMergedJobDataMap().getString("roleRemovalRoleKey");
	    String roleRemovalSpecificSites = jobExecutionContext.getMergedJobDataMap().getString("roleRemovalSpecificSites");
	    String roleRemovalSiteType = jobExecutionContext.getMergedJobDataMap().getString("roleRemovalSiteType");
	    String roleRemovalSiteCreationDate = jobExecutionContext.getMergedJobDataMap().getString("roleRemovalSiteCreationDate");
	    String roleRemovalSiteName = jobExecutionContext.getMergedJobDataMap().getString("roleRemovalSiteName");	
	    String roleRemovalRealmIds = jobExecutionContext.getMergedJobDataMap().getString("roleRemovalRealmIds");
        Session session = sessionManager.getCurrentSession();
		int count = 0;
	    
	    
		try {
			session.setUserEid("admin");
			session.setUserId("admin");

			if (roleRemovalSiteName != null && !roleRemovalSiteName.isEmpty()) {
		    	List<String> sitesByName = getSitesByName(roleRemovalSiteName);
		    	count = sitesByName.size();
		    	removeRoleFromSitesBySiteIds(sitesByName, roleRemovalRoleKey);
		    }
	
		    if (roleRemovalSiteCreationDate != null && !roleRemovalSiteCreationDate.isEmpty()) {
	        	List<Site> sites = getSitesByCreationDate(roleRemovalSiteCreationDate);
	        	count = sites.size();
	        	removeRoleFromSitesBySites(sites, roleRemovalRoleKey);
	        }
		    
		    if (roleRemovalSiteType != null && !roleRemovalSiteType.isEmpty()) {
		    	List<String> sitesByType = getSitesByType(roleRemovalSiteType);
		    	count = sitesByType.size();
		    	removeRoleFromSitesBySiteIds(sitesByType, roleRemovalRoleKey);
		    }
		    
		    if (roleRemovalSpecificSites != null  && !roleRemovalSpecificSites.isEmpty()) {
		    	List<String> sitesById = getSitesByIds(roleRemovalSpecificSites);
		    	count = sitesById.size();
		    	removeRoleFromSitesBySiteIds(sitesById, roleRemovalRoleKey);
		    }
		    
		    if (roleRemovalRealmIds != null && !roleRemovalRealmIds.isEmpty()) {
		    	List<AuthzGroup> authzGroups = getRealmsById(roleRemovalRealmIds);
		    	count = authzGroups.size();
		    	removeRoleFromSitesByAuthzGroup(authzGroups, roleRemovalRoleKey);
		    }
		    
		} finally {
			session.clear();
			log.info(count + " sites/realms has been updated");
		}
	    	    	    
	}
	
	public List<String> getSitesByName (String nameCriteria){
		return siteService.getSiteIds(SelectionType.NON_USER, null, nameCriteria, null, SortType.NONE, null);
	}

	public List<Site> getSitesByCreationDate(String creationDate) {
		if (creationDate.isEmpty()) {
			log.debug("The date " + creationDate + " is empty or malformatted");
			return null;
		}

		PagingPosition paging = new PagingPosition(1, 500);
		List<String> siteIds;
		List<Site> sitesToReturn;
		Site site = null;
		Date createdOn = getDate(creationDate);

		do {
			siteIds = siteService.getSiteIds(SelectionType.NON_USER, null, null, null, SortType.CREATED_ON_DESC,
					paging);
			paging.adjustPostition(500);
			sitesToReturn = new ArrayList<Site>();
			for (String siteId : siteIds) {
				try {
					site = siteService.getSite(siteId);
					if (site.getCreatedTime() == null) {
						continue;
					}
					if (site.getCreatedDate().after(createdOn)) {
						sitesToReturn.add(site);
					}
				} catch (IdUnusedException e) {
					e.printStackTrace();
				}
			}
		} while (site.getCreatedDate().after(createdOn));

		return sitesToReturn;
	}

	public List<String> getSitesByType (String siteType){
		if (siteService.getSiteTypeStrings(siteType).isEmpty()) {
			log.debug("The type " + siteType + " does not exist");
			return null;
		}
		 return siteService.getSiteIds(SelectionType.NON_USER, siteType, null, null, SortType.NONE, null);
			
	}
	
	
	public List<String> getSitesByIds(String specificSites){
		List<String> specificSitesSet = new ArrayList<String>();
		String[] specificSitesArray = specificSites.split(",");
		for (String site: specificSitesArray) {
			if (siteService.siteExists(site)) {
				specificSitesSet.add(site);
			}else {
				log.debug("The site id " + site + " does not exist");
			}				
		}
		return specificSitesSet;
	}


	public List<AuthzGroup> getRealmsById (String realmIds){
		List<AuthzGroup> realms = new ArrayList<AuthzGroup>();
		String[] realmsArray = realmIds.split(",");
		for (String realmId: realmsArray) {
			try {
				realms.add(authzGroupService.getAuthzGroup(realmId));
			}catch (GroupNotDefinedException e) {
				log.debug("The site id " + realmId + " does not exist");
			}				
		}
		return realms;
	}
	
	public void removeRoleFromSitesBySiteIds (List<String> siteIds, String role) {
		Site site = null;
		Collection <Group> groupes = null;
		for (String siteId: siteIds) {
			try {
				site = siteService.getSite(siteId);
				site.removeRole(role);	
				groupes = site.getGroups();
				for (Group groupe: groupes) {
					groupe.removeRole(role);
				}
				siteService.save(site);
			} catch (IdUnusedException | PermissionException e) {
				log.info("The site " + siteId + " does not exist");
			}
		}
	}

	public void removeRoleFromSitesBySites (List<Site> sites, String role) {
		Collection <Group> groupes = null;
		for(Site site: sites) {
			site.removeRole(role);
			groupes = site.getGroups();
			for (Group groupe: groupes) {
				groupe.removeRole(role);
			}
			try {
				siteService.save(site);
			} catch (IdUnusedException | PermissionException e) {
				log.info("The site " + site.getId() + " can not be updated");
			}
		}
		
	}
	
	public void removeRoleFromSitesByAuthzGroup (List<AuthzGroup> authzGroups, String role) {
		for (AuthzGroup authzGroup: authzGroups) {
			authzGroup.removeRole(role);
			try {
				authzGroupService.save(authzGroup);
			} catch (GroupNotDefinedException | AuthzPermissionException e) {
				log.info("The authzGroup " + authzGroup + " does not exist");
			}
		}
	}

    private Date getDate(String date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        Date convertedDate = null;
        try {
            convertedDate = dateFormat.parse(date);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return convertedDate;

    }


}


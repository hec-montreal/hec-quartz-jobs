package ca.hec.jobs.impl.roles;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.AuthzPermissionException;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.authz.api.Role;
import org.sakaiproject.authz.api.RoleAlreadyDefinedException;
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

import ca.hec.jobs.api.roles.BackfillRoleJob;
import lombok.Setter;

public class BackfillRoleJobImpl implements BackfillRoleJob {


	@Setter
	private AuthzGroupService authzGroupService;
	
	@Setter
	private SiteService siteService;
	
    @Setter
    protected SessionManager sessionManager;
	
    private static Logger log = LoggerFactory.getLogger(BackfillRoleJobImpl.class);
	

    @Override
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
	    String roleKey = jobExecutionContext.getMergedJobDataMap().getString("roleKey");
	    String realmOrigin = jobExecutionContext.getMergedJobDataMap().getString("realmOrigin");
	    String siteChange = jobExecutionContext.getMergedJobDataMap().getString("siteChange");
	    String groupChange = jobExecutionContext.getMergedJobDataMap().getString("groupChange");
	    String specificSites = jobExecutionContext.getMergedJobDataMap().getString("roleSpecificSites");
	    String siteType = jobExecutionContext.getMergedJobDataMap().getString("roleSiteType");		
	    String siteCreationDate = jobExecutionContext.getMergedJobDataMap().getString("roleSiteCreationDate");
	    String siteName = jobExecutionContext.getMergedJobDataMap().getString("roleSiteName");	
	    String realmIds = jobExecutionContext.getMergedJobDataMap().getString("roleRealmIds");
        Session session = sessionManager.getCurrentSession();
		int count = 0;
	    
		try {
			session.setUserEid("admin");
			session.setUserId("admin");

			AuthzGroup authzGroup = authzGroupService.getAuthzGroup(realmOrigin);
			Role role = authzGroup.getRole(roleKey);
			
			if (role == null) {
				log.error("The role does not exist in the realm");
				return;
			}
			
			if (siteName != null && !siteName.isEmpty()) {
		    	List<Site> sitesByName = getSitesByName(siteName);
		    	count = sitesByName.size();
		    	addOrUpdateRoleBySites(sitesByName, role, siteChange, groupChange);
		    }
	
		    if (siteCreationDate != null && !siteCreationDate.isEmpty()) {
	        	List<Site> sites = getSitesByCreationDate(siteCreationDate);
	        	count += sites.size();
	        	addOrUpdateRoleBySites(sites, role, siteChange, groupChange);
	        }
		    
		    if (siteType != null && !siteType.isEmpty()) {
		    	List<Site> sitesByType = getSitesByType(siteType);
		    	count += sitesByType.size();
		    	addOrUpdateRoleBySites(sitesByType, role, siteChange, groupChange);
		    }
		    
		    if (specificSites != null  && !specificSites.isEmpty()) {
		    	List<Site> sitesById = getSitesByIds(specificSites);
		    	count += sitesById.size();
		    	addOrUpdateRoleBySites(sitesById, role, siteChange, groupChange);
		    }
		    
		    if (realmIds != null && !realmIds.isEmpty()) {
		    	List<AuthzGroup> authzGroups = getRealmsById(realmIds);
		    	count += authzGroups.size();
		    	addOrUpdateRoleFromSitesByAuthzGroup(authzGroups, role);
		    }

		} catch (GroupNotDefinedException e) {
			log.error("The realm id " + realmOrigin + " does not exist");
		} finally {
			session.clear();
			log.info(count + " sites/realms has been updated");

		}
	}
    
    
	private void addOrUpdateRoleFromSitesByAuthzGroup(List<AuthzGroup> authzGroups, Role role) {
		Role roleToUpdate = null;
		
		for (AuthzGroup authzGroup: authzGroups) {
			roleToUpdate = authzGroup.getRole(role.getId());
			
			if ( roleToUpdate == null) {
				try {
					Role newRole = authzGroup.addRole(role.getId());
					newRole.allowFunctions(role.getAllowedFunctions());
				} catch (RoleAlreadyDefinedException e) {
					log.error("The role already exist in the realm " + authzGroup.getId());
				}
				
			} else {
				roleToUpdate.disallowAll();
				roleToUpdate.allowFunctions(role.getAllowedFunctions());
			}
			
			try {
				authzGroupService.save(authzGroup);
			} catch (GroupNotDefinedException e) {
				e.printStackTrace();
			} catch (AuthzPermissionException e) {
				e.printStackTrace();
			}
		}
		
	}


	private void addOrUpdateRoleBySites(List<Site> sites, Role role, String siteChange, String groupChange) {
		Role roleToUpdate = null;
		Collection <AuthzGroup> authzGroups = null;
		AuthzGroup authzGroup = null;
		
		for(Site site: sites) {
			try {
				if (siteChange.equalsIgnoreCase("TRUE")) {
					authzGroup = authzGroupService.getAuthzGroup(site.getReference());
						roleToUpdate = authzGroup.getRole(role.getId());
					if (roleToUpdate == null) {
						try {
							authzGroup.addRole(role.getId(), role);
						} catch (RoleAlreadyDefinedException e) {
							e.printStackTrace();
						}
					} else {
						roleToUpdate.disallowAll();
						roleToUpdate.allowFunctions(role.getAllowedFunctions());
					}
					authzGroupService.save(authzGroup);
				}
			
				if (groupChange.equalsIgnoreCase("TRUE")) {
					authzGroups = authzGroupService.getAuthzGroups(site.getReference(), null);
					for(AuthzGroup authzgroup: authzGroups) {
						if (authzgroup.getId().endsWith(site.getId())) {
							continue;
						}
						roleToUpdate = authzgroup.getRole(role.getId());
						if (roleToUpdate == null) {
							try {
								authzgroup.addRole(role.getId(), role);
							} catch (RoleAlreadyDefinedException e) {
								e.printStackTrace();
							}
						} else {
							roleToUpdate.disallowAll();
							roleToUpdate.allowFunctions(role.getAllowedFunctions());
						}
						authzGroupService.save(authzgroup);
					}
				}
				
			} catch (GroupNotDefinedException | AuthzPermissionException e) {
				e.printStackTrace();
			} 

		}
	}


	

	public List<Site> getSitesByName (String nameCriteria){
		return siteService.getSites(SelectionType.NON_USER, null, nameCriteria, null, SortType.NONE, null);
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

	public List<Site> getSitesByType (String siteType){
		if (siteService.getSiteTypeStrings(siteType).isEmpty()) {
			log.debug("The type " + siteType + " does not exist");
			return null;
		}
		 return siteService.getSites(SelectionType.NON_USER, siteType, null, null, SortType.NONE, null);
			
	}
	
	
	public List<Site> getSitesByIds(String specificSites){
		List<Site> specificSitesSet = new ArrayList<Site>();
		String[] specificSitesArray = specificSites.split(",");
		for (String site: specificSitesArray) {
			try {
				specificSitesSet.add(siteService.getSite(site));
			} catch (IdUnusedException e) {
				log.info("The site " + site + " does not exist");
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

	public Role getRole (String roleKey, String realmId) {
		Role role = null;
		try {
			AuthzGroup authzGroup = authzGroupService.getAuthzGroup(realmId);
			role = authzGroup.getRole(roleKey);
		} catch (GroupNotDefinedException e) {
			e.printStackTrace();
		}
		
		return role;
	}
	
	public List<AuthzGroup> updateSiteOnlyRealms (List<Site> sites, Role role){
		List<AuthzGroup> siteOnlyRealms = new ArrayList<AuthzGroup>();
		AuthzGroup authzGroup = null;
		Role siteRole = null;
		for (Site site: sites) {
			try {
				authzGroup = authzGroupService.getAuthzGroup(site.getReference());
				siteRole = authzGroup.getRole(role.getId());
				if (siteRole == null) {
					try {
						authzGroup.addRole(role.getId());
					} catch (RoleAlreadyDefinedException e) {
						e.printStackTrace();
					}
				}else {
					siteRole.disallowAll();
					siteRole.allowFunctions(role.getAllowedFunctions());
				}
				
				authzGroupService.save(authzGroup);
			} catch (GroupNotDefinedException | AuthzPermissionException e) {
				e.printStackTrace();
			}
		}
		
		return siteOnlyRealms;
	}
	
	public List<AuthzGroup> getGroupOnlyRealms (List<Site> sites){
		List<AuthzGroup> groupOnlyRealms = new ArrayList<AuthzGroup>();
		for (Site site: sites) {
			
		}
		
		return groupOnlyRealms;
		
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

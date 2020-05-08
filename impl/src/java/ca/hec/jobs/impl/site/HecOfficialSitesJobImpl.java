package ca.hec.jobs.impl.site;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroup;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.AuthzPermissionException;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.coursemanagement.api.AcademicCareer;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CanonicalCourse;
import org.sakaiproject.coursemanagement.api.CourseManagementAdministration;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.CourseSet;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.EntityProducer;
import org.sakaiproject.entity.api.EntityTransferrer;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.exception.IdInvalidException;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.IdUsedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteAdvisor;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.util.ArrayUtil;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.site.HecOfficialSitesJob;
import lombok.Setter;

/**
 * Created by mame-awa.diop@hec.ca on 2017-02-07.
 */

public class HecOfficialSitesJobImpl implements HecOfficialSitesJob {
    private static Log log = LogFactory.getLog(HecOfficialSitesJobImpl.class);

    @Setter
    protected CourseManagementAdministration cmAdmin;
    @Setter
    protected CourseManagementService cmService;
    @Setter
    protected SiteService siteService;
    @Setter
    protected SessionManager sessionManager;
    @Setter
    protected ContentHostingService chs;
    @Setter
    protected EntityManager entityManager;
    @Setter
    protected SiteIdFormatHelper siteIdFormatHelper;
    @Setter
    protected AuthzGroupService authzGroupService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String sessionStart = context.getMergedJobDataMap().getString("officialSitesSessionStart");
        String sessionEnd = context.getMergedJobDataMap().getString("officialSitesSessionEnd");
        String courses = context.getMergedJobDataMap().getString("officialSitesCourses");
        String programs = context.getMergedJobDataMap().getString("officialSitesPrograms");
        String departments = context.getMergedJobDataMap().getString("officialSitesDepartments");
        String distinctSitesSections = context.getMergedJobDataMap().getString("distinctSitesSections");

        List<AcademicSession> selectedSessions = getSessions(sessionStart, sessionEnd);

        List<CourseOffering> selectedCO = getSelectedCO(selectedSessions, courses, programs, departments);

        Map<String, List<Section>> sitesToCreate;

        Session session = sessionManager.getCurrentSession();
        try {
            session.setUserEid("admin");
            session.setUserId("admin");
            for (CourseOffering courseOff: selectedCO){
                sitesToCreate = siteIdFormatHelper.getSitesToCreateForCourseOffering(courseOff, distinctSitesSections);
                                
                sitesToCreate.forEach((siteName,sections) -> createSite(siteName, sections));
             }
        } finally {
            session.clear();
        }

    }


    private List<AcademicSession> getSessions (String sessionStart, String sessionEnd){
        List<AcademicSession> offSessions = new ArrayList<AcademicSession>();
        Date startDate = getDate(sessionStart);
        Date endDate = getDate(sessionEnd);
        List<AcademicSession> allSessions = cmService.getAcademicSessions();

        for(AcademicSession session: allSessions){
            if (session.getStartDate().compareTo(startDate) >= 0 && session.getEndDate().compareTo(endDate) <= 0)
                offSessions.add(session);
        }
        //Retrieve current sessions if no session
        if (offSessions.size() == 0) {
            offSessions = cmService.getCurrentAcademicSessions();
        }

        return offSessions;
    }


    private List<CourseOffering> getSelectedCO (List<AcademicSession> selectedSessions, String courses, String programs, String departments){
        List<CourseOffering> selectedCO = new ArrayList<CourseOffering>();
        String [] selectedCourses, selectedPrograms, selectedDepartments;


        if (programs != null && !programs.isEmpty()){
            selectedPrograms = splitProperty(programs);
            selectedCO.addAll(getSelectedCOByPrograms(selectedPrograms, selectedSessions));
        }

        if (departments != null && !departments.isEmpty()){
            selectedDepartments = splitProperty(departments);
            selectedCO.addAll(getSelectedCOByDepartments(selectedDepartments, selectedSessions));
        }

        if (courses != null && !courses.isEmpty()) {
            selectedCourses = splitProperty(courses);
            selectedCO.addAll(getSelectedCOByCourses(selectedCourses, selectedSessions));
        }
       return selectedCO;
    }

    private List<CourseOffering> getSelectedCOByDepartments ( String [] selectedDepartments, List<AcademicSession> offSessions){
        List<CourseOffering> selectedCO = new ArrayList<CourseOffering>();
        CourseSet department = null;
        for (int i=0; i< selectedDepartments.length; i++){
            department = cmService.getCourseSet(selectedDepartments[i]);
            if (department == null){
                log.warn(selectedDepartments[i] + " is not a valid department");
                continue;
            }
            for (AcademicSession session: offSessions){
                selectedCO.addAll(cmService.findCourseOfferings(department.getEid(), session.getEid()));
            }
        }


        return selectedCO;
    }

    private List<CourseOffering> getSelectedCOByPrograms ( String [] selectedPrograms, List<AcademicSession> offSessions){
        List<CourseOffering> selectedCO = new ArrayList<CourseOffering>();
        AcademicCareer acadCareer = null;
        for (int i=0; i<selectedPrograms.length; i++){
            acadCareer = cmService.getAcademicCareer(selectedPrograms[i]);
            if (acadCareer == null){
                log.warn( acadCareer + " is not a valid program");
                continue;
            }

            for (AcademicSession session: offSessions){
                selectedCO.addAll(cmService.findCourseOfferingsByAcadCareerAndAcademicSession(acadCareer.getEid(), session.getEid()));
            }

        }

        return selectedCO;
    }

    private List<CourseOffering> getSelectedCOByCourses ( String [] selectedCourses,List<AcademicSession> offSessions ){
        List<CourseOffering> selectedCO= new ArrayList<CourseOffering>();
        List<CourseOffering> tempCO =  new ArrayList<CourseOffering>();
        List<CanonicalCourse> cannCourses = getCanonicalCourses(selectedCourses);
        for (CanonicalCourse cann: cannCourses){
            tempCO.addAll(cmService.getCourseOfferingsInCanonicalCourse(cann.getEid()));
        }
        for( CourseOffering courseOff: tempCO){
            for (AcademicSession session: offSessions){
                if ((courseOff.getAcademicSession().getEid()).equals(session.getEid()))
                    selectedCO.add(courseOff);
            }
        }


        return selectedCO;
    }

    private String[] splitProperty (String property){
        if (property != null)
            return property.split(",");
        return null;
    }

    private List<CanonicalCourse> getCanonicalCourses(String[] courses){
        List<CanonicalCourse> offCanonicalCourses = new ArrayList<CanonicalCourse>();
        for (String course: courses){
            if (cmService.isCanonicalCourseDefined(course))
                offCanonicalCourses.add(cmService.getCanonicalCourse(course));
            else
                log.info (course + " is not a valid session eid");
        }
        return offCanonicalCourses;
    }

    private Site createSite (String siteName, List<Section> sections){
        try {
        	Site createdSite = null;

            Site templateSite = siteService.getSite(HEC_TEMPLATE_SITE);

            if (!siteService.siteExists(siteName)) {
                createdSite = siteService.addSite(siteName, templateSite);

                //Copy template content
                copyContent(chs.getSiteCollection(templateSite.getId()), chs.getSiteCollection(createdSite.getId()));
            }
            else {
                createdSite = siteService.getSite(siteName);
            }

            //Associate to sections
            createdSite = setProviderId(createdSite, sections);

            //Set site properties
            setSiteProperties(createdSite, siteName, sections);

            //Save/Update site properties, tools and providerId
            siteService.save(createdSite);

            //Update site membership
			/*
			 * List <SiteAdvisor> siteAdvisors = siteService.getSiteAdvisors(); for
			 * (SiteAdvisor secManager: siteAdvisors) secManager.update(createdSite);
			 */
            return createdSite;
        } catch (IdUnusedException e) {
            log.error(HEC_TEMPLATE_SITE + " does not exist" + e.getMessage());
        } catch (IdUsedException e) {
           log.warn(siteName + "already exist");
        } catch (PermissionException e) {
            log.warn("You are not allowed to create " + siteName);
        } catch (IdInvalidException e) {
            log.error(HEC_TEMPLATE_SITE + " or " + siteName + " is not a valid siteId");
        }

        return null;
    }


    private void copyContent (String templateReference, String siteReference){
            EntityTransferrer et = null;
            List<EntityProducer> entityProducers = entityManager.getEntityProducers();

            for (EntityProducer entityP: entityProducers){
                if (entityP instanceof EntityTransferrer) {
                    et = (EntityTransferrer) entityP;
                    if (ArrayUtil.contains(et.myToolIds(), RESOURCES_TOOL_ID)){
                        et.transferCopyEntities(templateReference, siteReference, new Vector(), false);
                    }
                }
            }
    }


    private void setSiteProperties (Site site, String siteName,  List<Section> sections){
        Section sectionRef = sections.get(0);
        CourseOffering courseOffering = cmService.getCourseOffering(sectionRef.getCourseOfferingEid());

        site.setTitle(siteName);
        site.setShortDescription(sectionRef.getDescription());

        ResourcePropertiesEdit rpe = site.getPropertiesEdit();
        rpe.addProperty(Site.PROP_SITE_TERM, courseOffering.getAcademicSession().getTitle());
        rpe.addProperty(Site.PROP_SITE_TERM_EID, courseOffering.getAcademicSession().getEid());
        rpe.addProperty("title", courseOffering.getTitle());

        if (courseOffering.getLang().equals("en")) {
            rpe.addProperty("hec_syllabus_locale", "en_US");
        }
        else if (courseOffering.getLang().equals("es")) {
            rpe.addProperty("hec_syllabus_locale", "es_ES");
        }
        else {
            rpe.addProperty("hec_syllabus_locale", "fr_CA");
        }
	rpe.addProperty("tenjin_template", "1");
   }

   private Site setProviderId (Site site, List<Section> sections){
       String providerGroupId = "";
       String sectionEid = null;
       String siteProviderId = site.getProviderGroupId();
       List<String> addedProviderIds = new ArrayList<String>();
       
       for (Section section : sections) {
            sectionEid = section.getEid();
           if (!sectionEid.isEmpty() && !providerGroupId.contains(sectionEid)) {
               providerGroupId += sectionEid + "+";
           }
           if (siteProviderId == null || !siteProviderId.contains(sectionEid)) {
               addedProviderIds.add(sectionEid);
           }
       }
       if(providerGroupId.endsWith("+"))
           providerGroupId = providerGroupId.substring(0, providerGroupId.lastIndexOf("+"));

        if (!providerGroupId.equals(siteProviderId)) {
            for (String providerId : addedProviderIds) {
                // if we're updating the providerId, 
                // remove new provider ids from any existing realms
                Set<String> realms = authzGroupService.getAuthzGroupIds(providerId);
                Map<String, List<String>> providerIdsForRealms = 
                    authzGroupService.getProviderIDsForRealms(Arrays.asList(realms.toArray(new String[0])));

                providerIdsForRealms.forEach((realmId, providerIds)->updateProviderId(realmId, 
                    providerIds.stream().filter(s->!s.equals(providerId)).collect(Collectors.joining("+"))));
            }
           
           site.setProviderGroupId(providerGroupId);
			/*
			 * try { siteService.save(site); } catch (IdUnusedException e) {
			 * log.error(site.getId() + " does not exist" + e.getMessage()); } catch
			 * (PermissionException e) { log.error(" You are not allowed to update " +
			 * site.getId() + " : " + e.getMessage()); }
			 */
           
       }
        return site;

   }

    private void updateProviderId(String realmId, String providerId) {
        try {
            log.debug(String.format("Update provider id to %s for realm %s", providerId, realmId));
            AuthzGroup ag = authzGroupService.getAuthzGroup(realmId);
            ag.setProviderGroupId(providerId);
            authzGroupService.save(ag);
        } catch (GroupNotDefinedException | AuthzPermissionException e) {
            log.error("Problem removing provider id from existing sites, continue");
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

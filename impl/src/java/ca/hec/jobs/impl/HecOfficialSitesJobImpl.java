package ca.hec.jobs.impl;

import ca.hec.commons.utils.FormatUtils;
import ca.hec.jobs.api.HecOfficialSitesJob;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.coursemanagement.api.*;
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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.sakaiproject.component.section.sakai.SectionManagerImpl;

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

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String sessionStart = context.getMergedJobDataMap().getString("sessionStart");
        String sessionEnd = context.getMergedJobDataMap().getString("sessionEnd");
        String courses = context.getMergedJobDataMap().getString("courses");
        String programs = context.getMergedJobDataMap().getString("programs");
        String departments = context.getMergedJobDataMap().getString("departments");

        List<AcademicSession> selectedSessions = getSessions(sessionStart, sessionEnd);

        List<CourseOffering> selectedCO = getSelectedCO(selectedSessions, courses, programs, departments);

        Session session = sessionManager.getCurrentSession();
        try {
            session.setUserEid("admin");
            session.setUserId("admin");
            for (CourseOffering courseOff: selectedCO){
               createSite(courseOff);
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

    public Site createSite (CourseOffering courseOffering){
        try {
            Site createdSite = null;

            Site templateSite = siteService.getSite(HEC_TEMPLATE_SITE);
            String siteName = getSiteName(courseOffering);

            if (!siteService.siteExists(siteName)) {
                createdSite = siteService.addSite(getSiteName(courseOffering), templateSite);
            }
            else
                createdSite = siteService.getSite(siteName);

            //Set site properties
            setSiteProperties(createdSite, courseOffering);

            //Associate to sections
            setProviderId(createdSite, courseOffering);

            //Save3Update site properties, tools and providerId
            siteService.save(createdSite);

            //Update site membership
            List <SiteAdvisor> siteAdvisors = siteService.getSiteAdvisors();
            for (SiteAdvisor secManager: siteAdvisors)
                secManager.update(createdSite);

            return createdSite;
        } catch (IdUnusedException e) {
            log.error(HEC_TEMPLATE_SITE + " does not exist" + e.getMessage());
        } catch (IdUsedException e) {
           log.warn(getSiteName(courseOffering) + "already exist");
        } catch (PermissionException e) {
            log.warn("You are not allowed to create " + getSiteName(courseOffering));
        } catch (IdInvalidException e) {
            log.error(HEC_TEMPLATE_SITE + " or " + getSiteName(courseOffering) + " is not a valid siteId");
        }

        return null;
    }

    private void setSiteProperties (Site site, CourseOffering courseOffering){
        site.setTitle(getSiteName(courseOffering));
        ResourcePropertiesEdit rpe = site.getPropertiesEdit();
        rpe.addProperty(Site.PROP_SITE_TERM, courseOffering.getAcademicSession().getTitle());
        rpe.addProperty(Site.PROP_SITE_TERM_EID, courseOffering.getAcademicSession().getEid());
        rpe.addProperty("title", courseOffering.getTitle());

   }

   private void setProviderId (Site site, CourseOffering courseOffering){
       String providerGroupId = (site.getProviderGroupId() == null ? "": site.getProviderGroupId());
       String sectionEid = null;
       Set<Section> sections = cmService.getSections(courseOffering.getEid());
       for (Section section : sections) {
           updateSectionTitle(section);
           sectionEid = section.getEid();
           //TODO: Remove after tenjin deploy
           if (!sectionEid.isEmpty() && !sectionEid.endsWith("00") && !providerGroupId.contains(sectionEid)) {
               providerGroupId += section.getEid() + "+";
           }
           //TODO: Remove after tenjin deploy
           //Make sure coordinator is in course offering membership
           Set <Membership> courseOfferingCoordinator = cmService.getCourseOfferingMemberships(courseOffering.getEid());
           if (sectionEid.endsWith("00") || courseOfferingCoordinator.size() > 0){
               Set<Membership> coordinators  = cmService.getSectionMemberships(sectionEid);
               Set<Section> courseSections = cmService.getSections(courseOffering.getEid());
               //Remove all section memberships
               for (Membership coordinator: coordinators){
                   for ( Section courseSection : courseSections) {
                       if (cmService.isSectionDefined(courseSection.getEid()))
                       cmAdmin.removeSectionMembership(coordinator.getUserId(), courseSection.getEid());
                   }
               }

           }
       }
       if(providerGroupId.endsWith("+"))
           providerGroupId = providerGroupId.substring(0, providerGroupId.lastIndexOf("+"));

       if (providerGroupId.length() > 0)
           site.setProviderGroupId(providerGroupId);
   }

    //TODO: Remove after tenjin deploy
   private void updateSectionTitle(Section section){
       AcademicSession session = cmService.getCourseOffering(section.getCourseOfferingEid()).getAcademicSession();
       String [] courseAndSection = (section.getEid()).split(session.getEid());
       if (courseAndSection.length == 2){
           section.setTitle(courseAndSection[1]);
           cmAdmin.updateSection(section);
       }

   }

     public String getSiteName(CourseOffering courseOff) {
        String siteName = null;
        String canCourseId = (courseOff.getCanonicalCourseEid()).trim();
        AcademicSession session = courseOff.getAcademicSession();
        String sessionId = session.getEid();

        String courseId = FormatUtils.formatCourseId(canCourseId);
        String sessionTitle = getSessionName(session);
        String periode = null;

        if (sessionId.matches(".*[pP].*")) {
            periode = sessionId.substring(sessionId.length() - 2);
        }

        if (periode == null)
            siteName = courseId + "." + sessionTitle;
        else
            siteName = courseId + "." + sessionTitle + "." + periode;

        return siteName;
    }

    public String getSessionName(AcademicSession session) {
        String sessionName = null;
        String sessionId = session.getEid();
        Date startDate = session.getStartDate();
        String year = startDate.toString().substring(0, 4);

        if ((sessionId.charAt(3)) == '1')
            sessionName = WINTER + year;
        if ((sessionId.charAt(3)) == '2')
            sessionName = SUMMER + year;
        if ((sessionId.charAt(3)) == '3')
            sessionName = FALL + year;

        return sessionName;
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

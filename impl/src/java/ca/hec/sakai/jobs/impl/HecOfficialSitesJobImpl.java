package ca.hec.sakai.jobs.impl;

import ca.hec.sakai.jobs.api.HecOfficialSitesJob;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Created by 11091096 on 2017-02-07.
 */

public class HecOfficialSitesJobImpl extends AbstractHecQuartzJobImpl implements HecOfficialSitesJob {
    private static Log log = LogFactory.getLog(HecOfficialSitesJobImpl.class);


    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String sessions = context.getMergedJobDataMap().getString("sessions");
        String courses = context.getMergedJobDataMap().getString("courses");
        String programs = context.getMergedJobDataMap().getString("programs");
        String departments = context.getMergedJobDataMap().getString("departments");
       System.out.println (sessions + " la session");

       List<CourseOffering> selectedCO = getSelectedCO(sessions, courses, programs, departments);
        System.out.println(selectedCO);
        loginToSakai("admin");
        for (CourseOffering courseOff: selectedCO){
            createSite(courseOff);
        }
        logoutFromSakai();
    }


    private List<CourseOffering> getSelectedCO (String sessions, String courses, String programs, String departments){
        List<CourseOffering> selectedCO = new ArrayList<CourseOffering>();
        String [] selectedSessions, selectedCourses, selectedPrograms, selectedDepartments;

        if (sessions != null && !sessions.isEmpty()) {
            //Retrieve selected sessions
            selectedSessions = splitProperty(sessions);
            List<AcademicSession> offSessions = getSessions(selectedSessions);

        }

        if (programs != null && !programs.isEmpty()){
            //Retrieve selected programs

        }

        if (departments != null && !departments.isEmpty()){
            //Retrieve selected departments

        }

        if (courses != null && !courses.isEmpty()) {
            //Retrieve selected courses
            selectedCourses = splitProperty(courses);
            List<CanonicalCourse> offCanonicalCourses = getCanonicalCourses(selectedCourses);
            for(CanonicalCourse offCanCourse: offCanonicalCourses){
                selectedCO.addAll(cmService.findActiveCourseOfferingsInCanonicalCourse(offCanCourse.getEid()));
            }

            System.out.print(selectedCO);
        }



        return selectedCO;
    }

    private String[] splitProperty (String property){
        if (property != null)
            return property.split(",");
        return null;
    }

    private List<AcademicSession> getSessions (String[] sessions){
        List<AcademicSession> offSessions = new ArrayList<AcademicSession>();
        for (String session: sessions){
            if (cmService.isAcademicSessionDefined(session))
                offSessions.add(cmService.getAcademicSession(session));
            else
                log.info (session + " is not a valid session eid");
        }
        System.out.println(offSessions);
        return offSessions;
    }

    private List<CanonicalCourse> getCanonicalCourses(String[] courses){
        List<CanonicalCourse> offCanonicalCourses = new ArrayList<CanonicalCourse>();
        for (String course: courses){
            if (cmService.isCanonicalCourseDefined(course))
                offCanonicalCourses.add(cmService.getCanonicalCourse(course));
            else
                log.info (course + " is not a valid session eid");
        }
        System.out.println(offCanonicalCourses);
        return offCanonicalCourses;
    }

    private Site createSite (CourseOffering courseOffering){
        try {
            Site templateSite = siteService.getSite("hec-template");
            Site createdSite = siteService.addSite(courseOffering.getEid(), templateSite);
            createdSite.setProviderGroupId(courseOffering.getEid());

            //Set site properties
            createdSite.setTitle(courseOffering.getTitle());
            ResourcePropertiesEdit rpe = createdSite.getPropertiesEdit();
            rpe.addProperty(Site.PROP_SITE_TERM, courseOffering.getAcademicSession().getTitle());
            rpe.addProperty(Site.PROP_SITE_TERM_EID, courseOffering.getAcademicSession().getEid());

            //Associate to sections
            String providerGroupId = "";
            Set<Section> sections = cmService.getSections(courseOffering.getEid());
            for (Section section : sections) {
                //TODO: Remove after tenjin deploy
                if (providerGroupId.length() > 0 && !providerGroupId.endsWith("00"))
                    providerGroupId += "+";
                providerGroupId += section.getEid();
            }

            if (providerGroupId.length() > 0)
                createdSite.setProviderGroupId(providerGroupId);

            siteService.save(createdSite);
            return createdSite;
        } catch (IdUnusedException e) {
            e.printStackTrace();
        } catch (IdUsedException e) {
            e.printStackTrace();
        } catch (PermissionException e) {
            e.printStackTrace();
        } catch (IdInvalidException e) {
            e.printStackTrace();
        }

        return null;
    }
}

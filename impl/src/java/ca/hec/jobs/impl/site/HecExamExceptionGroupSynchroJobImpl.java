/******************************************************************************
 * $Id: $
 ******************************************************************************
 *
 * Copyright (c) 2020 The Sakai Foundation, The Sakai Quebec Team.
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.Enrollment;
import org.sakaiproject.coursemanagement.api.Membership;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.db.api.SqlReaderFinishedException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.cover.EmailService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.site.HecExamExceptionGroupSynchroJob;
import lombok.*;

/**
 *
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class HecExamExceptionGroupSynchroJobImpl implements HecExamExceptionGroupSynchroJob {

    private static Log log = LogFactory.getLog(HecExamExceptionGroupImpl.class);

    private static Boolean isRunning = false;

    // group property to show it was created by this job
    private static final String GROUP_PROP_EXCEPTION_GROUP = "group_prop_exception_group";

    private static final String STUDENT_ROLE = "Student";
    private static final String INSTRUCTOR_ROLE = "Instructor";

    @Setter
    private EmailService emailService;
    @Setter
    private SqlService sqlService;
    @Setter
    protected SiteService siteService;
    @Setter
    protected SessionManager sessionManager;
    @Setter
    protected UserDirectoryService userDirectoryService;
    @Setter
    protected SiteIdFormatHelper siteIdFormatHelper;
    @Setter
    protected CourseManagementService cmService;
    @Setter
    protected ServerConfigurationService serverConfigService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Session session = sessionManager.getCurrentSession();
        String distinctSitesSections = context.getMergedJobDataMap().getString("distinctSitesSections");
        String subject = context.getMergedJobDataMap().getString("optionalSubject");
        String sessionId = context.getMergedJobDataMap().getString("sessionId");
        String siteId = null;
        String previousSiteId = null;
        Site site = null;
        Optional<Group> group = null;

        if (StringUtils.isBlank(sessionId)) {
            sessionId = cmService.getCurrentAcademicSessions().get(0).getEid();
            sessionId = StringUtils.chop(sessionId);
        }
        
        Map<String, List<String>> exceptionMap = new HashMap<>();
        List<ExceptedStudent> addedStudents = new ArrayList<>();
        List<ExceptedStudent> removedStudents = new ArrayList<>();
        
        if (isRunning) {
            log.error("HecCalendarEventsJob is already running, aborting.");
            return;
        } else {
            isRunning = true;
        }

        log.debug("starting");

        try {
            session.setUserEid("admin");
            session.setUserId("admin");

            List<Object> params = new ArrayList<Object>();
            String query = "select * from HEC_CAS_SPEC_EXM "
                    + " where (STATE is null or STATE <> 'E')";

            if (StringUtils.isNotBlank(sessionId)) {
                query += " and STRM=?";
                params.add(sessionId);
                log.debug("Handling session: " + sessionId);
            }
            if (StringUtils.isNotBlank(subject)) {
                query += " and SUBJECT=?";
                params.add(subject);
            }
            query += " order by SUBJECT, CATALOG_NBR, STRM, CLASS_SECTION, N_PRCENT_SUPP";

            List<ExceptedStudent> studentExceptions = sqlService.dbRead(query,
                    params.toArray(), new ExceptedStudentRecord());

            for (ExceptedStudent student : studentExceptions) {

                // build map of exceptions for synchronizing regular groups at the end.
                String officialProviderId = siteIdFormatHelper.buildSectionId(
                    student.getSubject() + student.getCatalogNbr(), student.getStrm(), SESSION_CODE, student.getClassSection());

                if (exceptionMap.containsKey(officialProviderId)) {
                    exceptionMap.get(officialProviderId).add(student.getEmplid());
                }
                else {
                    List<String> newList = new ArrayList<String>();
                    newList.add(student.getEmplid());
                    exceptionMap.put(officialProviderId, newList);
                }
                if (student.getState() == null) {
                    // nothing to do for null state
                    continue;
                }

                previousSiteId = siteId;
                siteId = siteIdFormatHelper.getSiteId(student.getSubject() + student.getCatalogNbr(), student.getStrm(),
                        SESSION_CODE, student.getClassSection(), distinctSitesSections);

                if (siteId == null) {
                    log.info("Le cours-section n'est pas encore dans le course management " + student.getSubject()
                            + student.getCatalogNbr() + student.getStrm() + SESSION_CODE + student.getClassSection());
                } else {
                    // We have changed site or have just started
                    if (!siteId.equals(previousSiteId)) {
                        if (saveSite(site)) {
                            deleteFromSyncTable(removedStudents);
                            clearState(addedStudents);
                        }
                        removedStudents.clear();
                        addedStudents.clear();

                        try {
                            log.info("On est dans le site " + siteId);
                            site = siteService.getSite(siteId);
                        } catch (IdUnusedException e) {
                            site = null;
                            log.info("Site does not exist");
                        }
                    } 

                    if (site == null) {
                        continue;
                    }

                    String groupTitle = generateGroupTitle(student.getClassSection(), student.getNPrcentSupp());
                    group = getGroupByTitle(site, groupTitle);

                    try {
                        String studentId = userDirectoryService.getUserId(student.getEmplid());

                        if (student.getState().equals(STATE_ADD)) {
                        
                            if (!group.isPresent()) {
                                group = createGroup(site, groupTitle);
                                addInstructor(site, group.get(), officialProviderId);
                            }

                            log.debug("Add student " + student.getEmplid() + " to group " + groupTitle + " in site " + site.getId());
                            group.get().insertMember(studentId, STUDENT_ROLE, true, false);
                            addedStudents.add(student);
                        } else if (student.getState().equals(STATE_REMOVE)) {
                            log.debug("Remove student " + student.getEmplid() + " from group " + groupTitle + " in site " + site.getId());
                            group.get().deleteMember(studentId);
                            removedStudents.add(student);
                        }
                    } catch (NoSuchElementException e) {
                        // The optional has no value
                        log.error("Tried to delete from a group that doesn't exist or failed to create group");
                    } catch (IllegalStateException e) {
                        log.error("Unable to modify group because it's locked");
                        //Make sure to send email before updating state
                        notifyError(student, siteId, group.get().getTitle());
                        setState(student, STATE_ERROR);
                    } catch (UserNotDefinedException e) {
                        log.error("User does not exist: " + student.getEmplid());
                    }
                }
            }
            // save the last site
            if (saveSite(site)) {
                deleteFromSyncTable(removedStudents);
                clearState(addedStudents);    
            }

            createOrUpdateRegularGroups(exceptionMap, distinctSitesSections);
        } finally {
            session.clear();
            isRunning = false;
            log.debug("finished");
        }
    }

    private void createOrUpdateRegularGroups(Map<String, List<String>> exceptionMap, String distinctSitesSections) {
        for(String providerId : exceptionMap.keySet()) {
            log.debug("Sync regular group for section " + providerId);
            try {
                List<String> exceptionsList = exceptionMap.get(providerId);
                // use CM because official group is sometimes not refreshed
                Set<Enrollment> enrollments = cmService.getEnrollments(providerId);
                Section section = cmService.getSection(providerId);
                String siteId = siteIdFormatHelper.getSiteId(section, distinctSitesSections);
                Site site = siteService.getSite(siteId);
                String regularGroupTitle = generateGroupTitle(section.getTitle(), null);

                Optional<Group> g = getGroupByTitle(site, regularGroupTitle);
                if (!g.isPresent()) {
                    g = createGroup(site, regularGroupTitle);
                }

                if (g.isPresent()) {
                    Set<String> existingMembers = g.get().getMembers().stream().map(member -> member.getUserId()).collect(Collectors.toSet());

                    for (Enrollment e : enrollments) {
                        try {
                            User u = userDirectoryService.getUserByEid(e.getUserId());
                            if (!e.isDropped() && !exceptionsList.contains(u.getEid())) {
                                if (!g.get().hasRole(u.getId(), STUDENT_ROLE)) {
                                    g.get().addMember(u.getId(), STUDENT_ROLE, true, false);
                                    log.debug("add member " + u.getEid() + " to " + site.getId() + " " + regularGroupTitle);
                                }
                                existingMembers.remove(u.getId());
                            }
                        }
                        catch (UserNotDefinedException unde) {
                            log.error("User not defined: " + e.getUserId());
                        }
                    }
                    Set<String> addedInstructors = addInstructor(site, g.get(), providerId);
                    existingMembers.removeAll(addedInstructors);

                    // existing members that are still in the list should be removed (no longer official)
                    for (String leftToDelete : existingMembers) {
                        g.get().removeMember(leftToDelete);
                        log.debug("remove member " + leftToDelete + " from " + site.getId() + " group " + regularGroupTitle);
                    }
                }
                saveSite(site);
            }
            catch (IdUnusedException | IdNotFoundException e) {
                log.error("Error retrieving site for regular group for provider: " + providerId);
            }
        }
    }

    private void deleteFromSyncTable(List<ExceptedStudent> removedStudents) {
        String sql = "delete from HEC_CAS_SPEC_EXM "+
            "where EMPLID=? and CATALOG_NBR=? and STRM=? and CLASS_SECTION=? and N_PRCENT_SUPP=? and STATE = 'D' "; 
            //state = D just to be safe

        for (ExceptedStudent student : removedStudents) {
            sqlService.dbWrite(sql, 
                new Object[] { 
                    student.getEmplid(), 
                    student.getCatalogNbr(), 
                    student.getStrm(), 
                    student.getClassSection(),
                    student.getNPrcentSupp()});
        }
    }

    private boolean saveSite(Site site) {
        if (site == null) 
            return false;

        log.debug("Save site: " + site.getId());
        try {
            siteService.save(site); 
        } catch (Exception e) {
            log.error("Site save failed", e);
            return false;
        }
        return true;
    }

    private void clearState(List<ExceptedStudent> addedStudents) {
        for (ExceptedStudent student : addedStudents) {
            setState(student, null);
        }
    }

    private boolean setState(ExceptedStudent student, String state) {
        String sql = "update HEC_CAS_SPEC_EXM set STATE = ? "+
            "where EMPLID=? and CATALOG_NBR=? and STRM=? and CLASS_SECTION=? and N_PRCENT_SUPP=?";

        return sqlService.dbWrite(sql, new Object[] {
            state,
            student.getEmplid(),
            student.getCatalogNbr(),
            student.getStrm(),
            student.getClassSection(),
            student.getNPrcentSupp()});
    }

    private String generateGroupTitle(String section, String percent) {
        if (StringUtils.isNotEmpty(percent))
            return EXCEPTION_GROUP_PREFIX + section  + percent.replace("%", "");
        else return EXCEPTION_GROUP_PREFIX + section  + REGULAR_GROUP_SUFFIX;
    }

    private Optional<Group> createGroup(Site site, String groupTitle) {
        log.debug("Create group " + groupTitle + " in site " + site.getId());
        Group group = site.addGroup();
        group.setTitle(groupTitle);
        group.getProperties().addProperty(Group.GROUP_PROP_WSETUP_CREATED, Boolean.TRUE.toString());
        group.getProperties().addProperty(GROUP_PROP_EXCEPTION_GROUP, Boolean.TRUE.toString());
        return Optional.of(group);
    }
    
    private Optional<Group> getGroupByTitle(Site site, String title) {
        return site.getGroups().stream()
            .filter(group -> (group.getTitle().equals(title)))
            .findFirst();
    }

    private Optional<Group> getGroupByProviderId(Site site, String providerId) {
        if (site == null || providerId == null) {
            return Optional.empty();
        }
        return site.getGroups().stream()
            .filter(group -> (group.getProperties().getProperty(Group.GROUP_PROP_WSETUP_CREATED) == null && providerId.equals(group.getProviderGroupId())))
            .findFirst();
    }

    private Set<String> addInstructor(Site site, Group group, String providerId) {
        Optional<Group> providedGroup = getGroupByProviderId(site, providerId);
	    String[] rolesToAdd = null;
        Set<String> addedUsers = new HashSet<String>();
    
	    if (providedGroup.isPresent()) {
            try {
                rolesToAdd = serverConfigService
                    .getStrings("hec.eventprocessing.groupeventprocessor.instructor.roles");
                    
	            if (rolesToAdd != null && rolesToAdd.length > 0) {
		            for (int i = 0; i < rolesToAdd.length; i++) {
                        Set<String> usersToAdd = providedGroup.get().getUsersHasRole(rolesToAdd[i]);
                        for (String user : usersToAdd) {
                            if (!group.hasRole(user, rolesToAdd[i])) {
                                group.insertMember(user, rolesToAdd[i], true, false);
                            }
                            addedUsers.add(user);
		                }
		            }
	            }

                // for some reason official instructors can be missing from group 
                // because it's not refreshed from CM so get them there
                Set<Membership> instructors = cmService.getSectionMemberships(providerId);
                for (Membership instructor : instructors) {
                    try {
                        String instructorId = userDirectoryService.getUserId(instructor.getUserId());
                        if (instructor.getRole().equals("I")) {
                            if (group.hasRole(instructorId, INSTRUCTOR_ROLE)) {
                                group.insertMember(instructorId, INSTRUCTOR_ROLE, true, false);
                            }
                            addedUsers.add(instructorId);
                        }
                    } catch (UserNotDefinedException e) {
                        log.warn("the instructor " + instructor.getUserId() + " does not exist");
                    }
                }
            }
            catch (IllegalStateException e) {
                log.error("Unable to modify group because it's locked: " + group.getId());
            }
        }
        return addedUsers;
    }

    // For now only one type of message because it will probably not be used
    // Later we can refine message structure and translation
    private void notifyError(ExceptedStudent student, String siteId, String groupTitle) {
        String from = "zonecours@hec.ca";
        //Make sure no email is null and no duplicates
        Set <String> emailSet = new HashSet <String>();
        if (!StringUtils.isEmpty(student.getNListeEmailProf())) {
            emailSet.add(student.getNListeEmailProf());
        }
        if (!StringUtils.isEmpty(student.getNListeEmailCoord())) {
            emailSet.add(student.getNListeEmailCoord());
        }
        if (!StringUtils.isEmpty(student.getNListeEmailAdj())) {
            emailSet.add(student.getNListeEmailAdj());
        }

        String action = (student.getState().equals(STATE_REMOVE) ? "retiré d'une" : "ajouté à une");
        String subject = "L'étudiant " + student.getName() + " (" + student.getEmplid() + ") n'a pas été " + action
                + " équipe automatique (" + groupTitle + ") pour le cours " + siteId;
        String to = emailSet.toString()
        	    .replace("[", "")  //remove the right bracket
        	    .replace("]", "")  //remove the left bracket
        	    .trim();  
        
        String message = "L’étudiant " + student.getName() + " (" + student.getEmplid() + ") n’a pas été " + action
                + " équipe automatique (" + groupTitle + ") pour le cours " + siteId
                + " parce que l'équipe ne peut être modifiée car elle est actuellement utilisée.\r\n" + "\r\n"
                + "Si le groupe est associé à un travail ou quiz publié et en cours, nous vous conseillons de créer un autre groupe pour cet étudiant. Pour les évaluations à venir, assurez-vous de les publier pour ces nouveaux groupes.\r\n"
                + "Si le groupe est associé à une évaluation  publiée mais pas encore en cours, nous vous suggérons de dépublier l’évaluation et d’ajouter manuellement l’étudiant dans le groupe. Assurez-vous de republier l’évaluation une fois les changements terminés.\r\n"
                + "\r\n" + "Cordialement,\r\n" + "\r\n"
                + "P.S. : Ce courriel est envoyé par un processus automatisé de création de groupes pour les étudiants en situation d’handicap. \r\n";

        if (emailSet.size() > 0) {
            emailService.send(from, to, subject, message, null, null, null);
        }
    }
    
    @Data
    private class ExceptedStudent {
        String strm, emplid, name, nPrcentSupp, acadCareer, subject, catalogNbr, classSection, state,
            nListeEmailAdj, nListeEmailProf, nListeEmailCoord;
    }

    private class ExceptedStudentRecord implements SqlReader<ExceptedStudent> {

        @Override
        public ExceptedStudent readSqlResultRecord(ResultSet rs) throws SqlReaderFinishedException {
            ExceptedStudent student = new ExceptedStudent();

            try {
                student.setStrm(rs.getString("STRM"));
                student.setEmplid(rs.getString("EMPLID"));
                student.setName(rs.getString("Name"));
                student.setNPrcentSupp(rs.getString("N_PRCENT_SUPP"));
                student.setAcadCareer(rs.getString("ACAD_CAREER"));
                student.setSubject(rs.getString("SUBJECT"));
                student.setCatalogNbr(rs.getString("CATALOG_NBR"));
                student.setClassSection(rs.getString("CLASS_SECTION"));
                student.setState(rs.getString("STATE"));
                student.setNListeEmailAdj(rs.getString("N_EMAIL_ADJ_PRINC"));
                student.setNListeEmailProf(rs.getString("N_LISTE_EMAIL_PROF"));
                student.setNListeEmailCoord(rs.getString("N_EMAIL_COORD"));
            }
            catch (SQLException e) {
                log.error("Error retrieving HecStudent record");
                e.printStackTrace();
                return null;
            }
            return student;
		}
    }
}


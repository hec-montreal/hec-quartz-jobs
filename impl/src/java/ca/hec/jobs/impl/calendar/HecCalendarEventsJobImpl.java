/******************************************************************************
 * $Id: $
 ******************************************************************************
 *
 * Copyright (c) 2013 The Sakai Foundation, The Sakai Quebec Team.
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
package ca.hec.jobs.impl.calendar;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.calendar.HecCourseEventSynchroJob;
import ca.hec.jobs.api.calendar.HecCalendarEventsJob;
import lombok.Data;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.calendar.api.Calendar;
import org.sakaiproject.calendar.api.CalendarEvent;
import org.sakaiproject.calendar.api.CalendarEventEdit;
import org.sakaiproject.calendar.api.CalendarService;
import org.sakaiproject.coursemanagement.api.CourseManagementAdministration;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.db.api.SqlReaderFinishedException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.entity.cover.EntityManager;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.javax.PagingPosition;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.site.api.SiteService.SortType;
import org.sakaiproject.time.cover.TimeService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author curtis.van-osch@hec.ca
 * @version $Id: $
 */
public class HecCalendarEventsJobImpl implements HecCalendarEventsJob {

    private static Log log = LogFactory.getLog(HecCalendarEventsJobImpl.class);
    private static PropertiesConfiguration propertiesEn = null;
    private static PropertiesConfiguration propertiesFr = null;

    private static Boolean isRunning = false;
	
    private final String EVENT_TYPE_CLASS_SESSION = "Class session";
    private final String EVENT_TYPE_EXAM = "Exam";
    private final String EVENT_TYPE_QUIZ = "Quiz";
    private final String EVENT_TYPE_SPECIAL = "Special event";
    private final String PSFT_EXAM_TYPE_INTRA = "INTR";
    private final String PSFT_EXAM_TYPE_FINAL = "FIN";
    private final String PSFT_EXAM_TYPE_TEST = "TEST";
    private final String PSFT_EXAM_TYPE_QUIZ = "QUIZ";

    @Setter
    private CalendarService calendarService;
    @Setter
    protected SiteIdFormatHelper siteIdFormatHelper;
    @Setter
    protected CourseManagementAdministration cmAdmin;
    @Setter
    protected CourseManagementService cmService;
    @Setter
    protected SiteService siteService;
    @Setter
    protected SessionManager sessionManager;
    @Setter
    protected HecCourseEventSynchroJob courseEventSynchroJob;
    @Setter
    private SqlService sqlService;

    public void init() {
    	URL url;

    	try {
    		url = getClass().getClassLoader().getResource("ca/hec/jobs/bundle/JobMessages.properties");
    		propertiesEn = new PropertiesConfiguration();
    		propertiesEn.load(url);
    		
    		url = getClass().getClassLoader().getResource("ca/hec/jobs/bundle/JobMessages_fr_CA.properties");
    		propertiesFr = new PropertiesConfiguration();
    		propertiesFr.load(url);
    	}
    	catch (ConfigurationException e) {
    		log.error("Cannot load properties message files");
    	}
    }
    
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("starting CreateCalendarEventsJob");
        String startDateString = context.getMergedJobDataMap().getString("startDate");
        Date startDate = getDate(startDateString);

        if (isRunning) {
            log.error("HecCalendarEventsJob is already running, aborting.");
            return;
        }

        try {
            courseEventSynchroJob.execute(context);
        } catch (Exception e) {
            e.printStackTrace();
        }

        String distinctSitesSections = context.getMergedJobDataMap().getString("distinctSitesSections");
        int addcount = 0, updatecount = 0, deletecount = 0;
        Session session = sessionManager.getCurrentSession();
        Date lastRun = null;
        
        if (propertiesEn == null || propertiesFr == null) {
        	log.error("messages are not defined!");
        	return;
        }
        
        try {
            session.setUserEid("admin");
            session.setUserId("admin");

            String select_from = "select CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, CLASS_EXAM_TYPE, SEQ, DATE_HEURE_DEBUT, "
                    + "DATE_HEURE_FIN, DESCR_FACILITY, STATE, DESCR, EVENT_ID from HEC_EVENT ";
            String order_by = " order by CATALOG_NBR, STRM, CLASS_SECTION ";

            List<HecEvent> eventsAdd = sqlService.dbRead(
                    select_from + "where EVENT_ID is null and (STATE = 'A' or STATE = 'M')" + order_by, null,
                    new HecEventRowReader());

            // also add events for new sites created since last run or specified date
            lastRun = getLastRunDate();
            if (startDate == null) {
                startDate = lastRun;
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss");
            log.info("Handle events for sites created since " + sdf.format(startDate));
            eventsAdd.addAll(getEventsForNewSites(startDate));

            // keep track of the last event's site id, calendar and courseOffering, so we can use the calendar if it was already found
            Calendar calendar = null;
            String previousSiteId = "";
            boolean calendarFound = false;
            CourseOffering courseOffering = null;
            Group eventGroup = null;
            Site site = null;

            log.info("loop and add " + eventsAdd.size() + " events");
            for (HecEvent event : eventsAdd) {
                String siteId = siteIdFormatHelper.getSiteId(
                        event.getCatalogNbr(),
                        event.getSessionId(),
                        event.getSessionCode(), event.getSection(), distinctSitesSections);

                //Section does not exist
                if (siteId == null) {
                    clearHecEventState( event.getEventId(), event.getCatalogNbr(), event.getSessionId(),
                            event.getSessionCode(), event.getSection(), event.getExamType(),
                            event.getSequenceNumber());
                    continue;
                }

                String eventId = null;

                if (!siteId.equals(previousSiteId)) {
                    // this is a new site id, calendar not found yet
                    calendarFound = false;
                    courseOffering = null;

                    try {
                        calendar = getCalendar(siteId);
                        calendarFound = true;

                        // retrieve course offering to see if the course is MBA
                        site = siteService.getSite(siteId);
                        eventGroup = getGroup(site.getGroups(), event.getSection());
                        //Section is not associated to site
                        if (eventGroup == null){
                            clearHecEventState( event.getEventId(), event.getCatalogNbr(), event.getSessionId(),
                                    event.getSessionCode(), event.getSection(), event.getExamType(),
                                    event.getSequenceNumber());
                            continue;
                        }
                        Section section = cmService.getSection(eventGroup.getProviderGroupId());
                        courseOffering = cmService.getCourseOffering(section.getCourseOfferingEid());

                    } catch (IdNotFoundException e) {
                        log.debug("Site " + siteId + " not associated to course management");
                    } catch (IdUnusedException e) {
                        log.debug("Site or Calendar for " + siteId + " does not exist (for section " + event.getSection() + ")");
                    } catch (PermissionException e) {
                        e.printStackTrace();
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // only attempt event creation if the calendar was found
                if (courseOffering != null && calendarFound) {

                    boolean createEvent = true;

                    if (event.getStartTime().getYear() != event.getEndTime().getYear() ||
                            event.getStartTime().getMonth() != event.getEndTime().getMonth() ||
                            event.getStartTime().getDate() != event.getEndTime().getDate()) {

                        createEvent = false;
                        log.debug("Skipping event creation: " + getEventTitle(siteId, event.getExamType(), event.getSequenceNumber()) +
                                " for site " + siteId + " (end date is after start date)");
                    }

                    // don't bother adding the events if this is DF (ZCII-1665)
                    // and the event is not a final or mid-term (intratrimestriel) exam
                    if ((siteId.contains("DF")) &&
                            !event.getExamType().equals(PSFT_EXAM_TYPE_INTRA) &&
                            !event.getExamType().equals(PSFT_EXAM_TYPE_FINAL)) {
                        createEvent = false;
                        log.debug("Skipping event creation: " + getEventTitle(siteId, event.getExamType(), event.getSequenceNumber()) +
                                " for site " + siteId + " (course is DF and event is not an exam)");
                    }

                    if (createEvent) {
                        eventGroup = getGroup(site.getGroups(), event.getSection());
                        eventId = createCalendarEvent(
                                calendar,
                                event.getStartTime(),
                                event.getEndTime(),
                                getEventTitle(siteId, event.getExamType(), event.getSequenceNumber()),
                                getType(event.getExamType()),
                                event.getLocation(),
                                event.getDescription(), eventGroup);
                    }
                }

                // clear the state in HEC_EVENT regardless
                if (!clearHecEventState(
                        eventId,
                        event.getCatalogNbr(),
                        event.getSessionId(),
                        event.getSessionCode(),
                        event.getSection(),
                        event.getExamType(),
                        event.getSequenceNumber())) {

                    throw new JobExecutionException();
                }

                if (eventId != null) {
                    addcount++;
                }

                previousSiteId = siteId;
            }

            List<HecEvent> eventsUpdate = sqlService.dbRead(
                    select_from + "where (STATE = 'M' or STATE = 'D')" + order_by,
                    null,
                    new HecEventRowReader());

            log.info("loop and update " + eventsUpdate.size() + " events");
            for (HecEvent event : eventsUpdate) {
                String siteId = siteIdFormatHelper.getSiteId(
                        event.getCatalogNbr(),
                        event.getSessionId(),
                        event.getSessionCode(), event.getSection(), distinctSitesSections);

                //Section does not exist
                if (siteId == null) {
                    deleteHecEvent(event.getCatalogNbr(), event.getSessionId(), event.getSessionCode(),
                            event.getSection(), event.getExamType(), event.getSequenceNumber());
                    continue;
                }

                boolean updateSuccess = false;

                if (!siteId.equals(previousSiteId)) {
                    // new site, calendar not yet found
                    calendarFound = false;
                    courseOffering = null;

                    try {
                        calendar = getCalendar(siteId);
                        calendarFound = true;

                        // retrieve course offering to see if the course is MBA
                        site = siteService.getSite(siteId);
                        eventGroup = getGroup(site.getGroups(), event.getSection());
                        //Section not associated to site
                        if (eventGroup == null){
                            if (event.getEventId() != null)
                                updateCalendarEvent(calendar, event.getEventId(), event.getState(),
                                        event.getStartTime(), event.getEndTime(), event.getLocation(),
                                        event.getDescription(), eventGroup);
                            deleteHecEvent(event.getCatalogNbr(), event.getSessionId(), event.getSessionCode(),
                                    event.getSection(), event.getExamType(), event.getSequenceNumber());
                            continue;
                        }
                        Section section = cmService.getSection(eventGroup.getProviderGroupId());
                        courseOffering = cmService.getCourseOffering(section.getCourseOfferingEid());

                    } catch (IdNotFoundException e) {
                        log.debug("Site " + siteId + " not associated to course management");
                    } catch (IdUnusedException e) {
                        log.debug("Site or Calendar for " + siteId + " does not exist.");
                    } catch (PermissionException e) {
                        e.printStackTrace();
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (courseOffering != null && calendarFound) {

                    // don't bother adding the events if this is DF (ZCII-1665)
                    // and the event is not a final or mid-term (intratrimestriel) exam
                    if (( !siteId.contains("DF")) ||
                            event.getExamType().equals(PSFT_EXAM_TYPE_INTRA) ||
                            event.getExamType().equals(PSFT_EXAM_TYPE_FINAL)) {
                        eventGroup = getGroup(site.getGroups(), event.getSection());
                        updateSuccess = updateCalendarEvent(
                                calendar,
                                event.getEventId(),
                                event.getState(),
                                event.getStartTime(),
                                event.getEndTime(),
                                event.getLocation(),
                                event.getDescription(), eventGroup);
                    } else {
                        log.debug("Skipping event update: " + getEventTitle(siteId, event.getExamType(), event.getSequenceNumber()) +
                                " for site " + siteId + " (course is DF and event is not an exam)");
                    }
                }

                if (event.getState().equals("M")) {
                    if (!clearHecEventState(
                            event.getEventId(),
                            event.getCatalogNbr(),
                            event.getSessionId(),
                            event.getSessionCode(),
                            event.getSection(),
                            event.getExamType(),
                            event.getSequenceNumber())) {
                        throw new JobExecutionException();
                    }

                    if (updateSuccess)
                        updatecount++;
                } else if (event.getState().equals("D")) {
                    if (!deleteHecEvent(
                            event.getCatalogNbr(),
                            event.getSessionId(),
                            event.getSessionCode(),
                            event.getSection(),
                            event.getExamType(),
                            event.getSequenceNumber())) {
                        throw new JobExecutionException();
                    }

                    if (updateSuccess)
                        deletecount++;
                }

                previousSiteId = siteId;
            }

            if (lastRun == null) {
                insertLastRunDate();
            } else {
                updateLastRunDate();
            }
        } finally {
            session.clear();
            isRunning = false;
        }
        log.info("added: " + addcount + " updated: " + updatecount + " deleted: " + deletecount);
    } // execute

    private void updateLastRunDate() {
        try {
            sqlService.dbWrite(
                    "update HEC_JOBS_LOG set LASTRUNDATE = SYSDATE where JOB_ID = 'HecCalendarEventsJob'");
        } catch (DataAccessException e) {
            e.printStackTrace();
        }
    }

    private void insertLastRunDate() {
        try {
            sqlService.dbWrite(
                    "insert into HEC_JOBS_LOG (JOB_ID, LASTRUNDATE) values ('HecCalendarEventsJob', SYSDATE)");
        } catch (DataAccessException e) {
            e.printStackTrace();
        }
    }

    private List<HecEvent> getEventsForNewSites(Date startDate) {
        if (startDate == null) {
            // this returns them all
            return getEventsForCatalogNbr(null);
        }

        List<HecEvent> events = new ArrayList<HecEvent>();

        PagingPosition pp = new PagingPosition(1, 500);
        Set<String> catalogNumbers = new HashSet<String>();
        List<Site> sites = null;
        Boolean stop = false;
        do {
            sites = siteService.getSites(SelectionType.ANY, "course", null, null, SortType.CREATED_ON_DESC, pp);
            pp.adjustPostition(500);
            for (Site site : sites) {
                if (site.getCreatedDate().after(startDate)) {
                    String siteId = site.getId();
                    Integer lastPeriodIndex = siteId.indexOf('.');
                    if (lastPeriodIndex == -1) {
                    	continue;
                    }
                    	
                    String catalogNbr = siteId.substring(0, lastPeriodIndex);
                    if (lastPeriodIndex > 0 && !catalogNbr.isEmpty()) {
                        catalogNumbers.add(catalogNbr);
                    }
                } else {
                    stop = true;
                    break;
                }
            }
        } while (!stop);

        for (String catalogNumber : catalogNumbers) {
            events.addAll(getEventsForCatalogNbr(catalogNumber));
        }

        return events;
    }

    private Date getLastRunDate() {

        String select_from = "select LASTRUNDATE from HEC_JOBS_LOG where JOB_ID = 'HecCalendarEventsJob'";

        List<Date> rundates = sqlService.dbRead(
                select_from, null, new SqlReader<Date>() {

                    @Override
                    public Date readSqlResultRecord(ResultSet result) throws SqlReaderFinishedException {
                        try {
                            return result.getTimestamp("LASTRUNDATE");
                        } catch (SQLException e) {
                            e.printStackTrace();
                            return null;
                        }
                    }
                });

        if (rundates.size() == 0) {
            return null;
        }
        return rundates.get(0);
    }

    private List<HecEvent> getEventsForCatalogNbr(String catalogNbr) {

        String select_from = "select * from HEC_EVENT ";
        String where = null;
        String order_by = "order by CATALOG_NBR, STRM, CLASS_SECTION ";

        if (catalogNbr == null) {
            where = "where EVENT_ID is null and STATE is null ";
        } else {
            where = "where EVENT_ID is null and STATE is null and CATALOG_NBR = '" + catalogNbr +"' ";
        }

        List<HecEvent> events = sqlService.dbRead(
                select_from + where + order_by, null,
                new HecEventRowReader());

        return events;
    }

    private boolean clearHecEventState(String event_id, String catalog_nbr, String session_id, String session_code, String section,
                                       String exam_type, Integer sequence_num) {

        try {
            boolean success = sqlService.dbWrite(
                    "update HEC_EVENT set STATE = null, EVENT_ID = ? where CATALOG_NBR = ? and STRM = ? and " +
                            "SESSION_CODE = ? and CLASS_SECTION = ? and CLASS_EXAM_TYPE = ? and SEQ = ?",
                    new Object[]{event_id, catalog_nbr, session_id, session_code, section, exam_type, sequence_num});

            return success;

        } catch (DataAccessException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean deleteHecEvent(String catalog_nbr, String session_id, String session_code, String section,
                                   String exam_type, Integer sequence_num) {

        try {
            boolean success = sqlService.dbWrite("delete from HEC_EVENT where CATALOG_NBR = ? and STRM = ? and SESSION_CODE = ? and CLASS_SECTION = ? " +
                            "and CLASS_EXAM_TYPE = ? and SEQ = ?",
                    new Object[]{catalog_nbr, session_id, session_code, section, exam_type, sequence_num});

            return success;
        } catch (DataAccessException e) {
            e.printStackTrace();
            return false;
        }
    }

    private String createCalendarEvent(Calendar calendar, Date startTime, Date endTime, String title, String type, String location, String description, Group sectionGroup) {
        CalendarEvent event;

        List<Group> groups = new ArrayList<>();
            if (sectionGroup != null && sectionGroup.getProviderGroupId() != null)
                groups.add(sectionGroup);

            try {
                // add event to calendar
                event = calendar.addEvent(
                        TimeService.newTimeRange(TimeService.newTime(startTime.getTime()), TimeService.newTime(endTime.getTime()), true, false),
                        title,
                        description,
                        type,
                        location,
                        CalendarEvent.EventAccess.GROUPED,
                        groups,
                        EntityManager.newReferenceList());

            } catch (PermissionException e) {
                e.printStackTrace();
                return null;
            }

            log.debug("created event: " + title + " in site " + calendar.getContext() + " for sections " + event.getGroups());

            return event.getId();
        }

    private boolean updateCalendarEvent(Calendar calendar, String eventId, String state,
                                        Date newStartTime, Date newEndTime, String newLocation, String newDescription, Group sectionGroup) {
        if (eventId == null)
            return false;

        CalendarEventEdit edit;

            try {
                edit = calendar.getEditEvent(eventId, CalendarService.EVENT_MODIFY_CALENDAR);
            } catch (IdUnusedException e) {
                log.debug("Event " + eventId + " does not exist");
                return false;
            } catch (NullPointerException e) {
                log.debug("Event " + eventId + " does not exist");
                return false;
            } catch (Exception e) {
                log.error("Error retrieving event " + eventId);
                e.printStackTrace();
                return false;
            }

            if (state.equals("M")) {
                if (newStartTime != null && newEndTime != null)
                    edit.setRange(TimeService.newTimeRange(TimeService.newTime(newStartTime.getTime()), TimeService.newTime(newEndTime.getTime()), true, false));
                if (newLocation != null)
                    edit.setLocation(newLocation);
                if (newDescription != null)
                    edit.setDescription(newDescription);

                //Make sure we update group
                try {
                    List<Group> groups = new ArrayList<>();
                    groups.add(sectionGroup);
                    edit.setGroupAccess(groups, true);
                } catch (PermissionException e) {
                    log.error("User doesn't have permission to update group access for event " + eventId);
                } catch (NullPointerException e) {
                    log.error("Section " + sectionGroup + " does not exist in site for for event " + eventId);
                    e.printStackTrace();
                } catch (Exception e) {
                    log.error("Exception when updating group access for event " + eventId);
                    e.printStackTrace();
                }

            } else if (state.equals("D")) {
                try {
                    calendar.removeEvent(edit);
                } catch (PermissionException e) {
                    log.error("User doesn't have permission to delete event " + eventId);
                    return false;
                }
            }

            calendar.commitEvent(edit);
            log.debug("updated (" + state + ") event: " + edit.getDisplayName() + " in site " + calendar.getContext() + " for section " + edit.getGroups());

        return true;
    }

    private Calendar getCalendar(String siteId) throws IdUnusedException, PermissionException {
        if (siteService.siteExists(siteId)) {
            String calRef = calendarService.calendarReference(siteId, siteService.MAIN_CONTAINER);
            return calendarService.getCalendar(calRef);
        } else {
            throw new IdUnusedException("Site does not exist");
        }
    }


    private String getEventTitle(String siteId, String type, Integer seq_num) {

        Site site = null;
        String courseSiteTittle = "";
        String locale = "fr_CA";

        try {
            site = siteService.getSite(siteId);
            courseSiteTittle = site.getProperties().getPropertyFormatted("title");
            locale = site.getProperties().getProperty("hec_syllabus_locale");
        } catch (IdUnusedException e) {
            log.error("The site " + siteId + "does not exist");
        }

        PropertiesConfiguration msgs = null;
        if (locale.equals("en_US")) {
        	msgs = propertiesEn;
        }
        else {
        	msgs = propertiesFr;
        }
        
        if (type.equals(" ")) {
            if (courseSiteTittle != "")
                return (courseSiteTittle + " (" + msgs.getString("calendar.event-title.session") + " " + seq_num + ")");
            else
                return msgs.getString("calendar.event-title.session") + " " + seq_num;
        } else if (type.equals(PSFT_EXAM_TYPE_INTRA)) {
            if (courseSiteTittle != "")
                return (courseSiteTittle + " (" + msgs.getString("calendar.event-title.intra") + ")");
            else
                return msgs.getString("calendar.event-title.intra");
        } else if (type.equals(PSFT_EXAM_TYPE_FINAL)) {
            if (courseSiteTittle != "")
                return (courseSiteTittle + " (" + msgs.getString("calendar.event-title.final") + ")");
            else
                return msgs.getString("calendar.event-title.final");
        } else if (type.equals(PSFT_EXAM_TYPE_TEST) || type.equals(PSFT_EXAM_TYPE_QUIZ)) {
            if (courseSiteTittle != "")
                return (courseSiteTittle + " (" + msgs.getString("calendar.event-title.test") + ")");
            else
                msgs.getString("calendar.event-title.test");
        } else {
            if (courseSiteTittle != "")
                return courseSiteTittle + " (" + type + ")";
        }

        return type;
    }

    private String getType(String exam_type) {
        if (exam_type.equals(" "))
            return EVENT_TYPE_CLASS_SESSION;
        else if (exam_type.equals(PSFT_EXAM_TYPE_INTRA) || exam_type.equals(PSFT_EXAM_TYPE_FINAL))
            return this.EVENT_TYPE_EXAM;
        else if (exam_type.equals(PSFT_EXAM_TYPE_TEST) || exam_type.equals(PSFT_EXAM_TYPE_QUIZ))
            return EVENT_TYPE_QUIZ;
        else
            return EVENT_TYPE_SPECIAL;
    }

    @Data
    private class HecEvent {
        String catalogNbr, sessionId, sessionCode, section, state, examType, location, description, eventId;
        Integer sequenceNumber;
        Date startTime, endTime;
    }

    private class HecEventRowReader implements SqlReader<HecEvent> {

        @Override
        public HecEvent readSqlResultRecord(ResultSet rs) throws SqlReaderFinishedException {
            HecEvent event = new HecEvent();

            try {
                event.setCatalogNbr(rs.getString("CATALOG_NBR"));
                event.setSessionId(rs.getString("STRM"));
                event.setSessionCode(rs.getString("SESSION_CODE"));
                event.setSection(rs.getString("CLASS_SECTION"));
                event.setExamType(rs.getString("CLASS_EXAM_TYPE"));
                event.setSequenceNumber(rs.getInt("SEQ"));
                event.setStartTime(rs.getTimestamp("DATE_HEURE_DEBUT"));
                event.setEndTime(rs.getTimestamp("DATE_HEURE_FIN"));
                event.setLocation(rs.getString("DESCR_FACILITY"));
                event.setDescription(rs.getString("DESCR"));
                event.setEventId(rs.getString("EVENT_ID"));
                event.setState(rs.getString("STATE"));
            }
            catch (SQLException e) {
                log.error("Error retrieving HecEvent record");
                e.printStackTrace();
                return null;
            }
            return event;
		}

    }

    private Group getGroup(Collection<Group> siteGroups, String eventSection) {
        String providerId = null;
        for (Group siteGroup : siteGroups) {
            providerId = siteGroup.getProviderGroupId();
            if (providerId != null && providerId.endsWith(eventSection))
                return siteGroup;
        }

        return null;
    }
    
    private Date getDate(String date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        Date convertedDate = null;
        try {
            convertedDate = dateFormat.parse(date);
        } catch (ParseException e) {
            log.debug("Unparseable date: "+date);
        }
        return convertedDate;

    }
}

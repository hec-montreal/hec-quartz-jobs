package ca.hec.jobs.api.calendar;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Job de synchro du fichier d'extract contenant les événements de cours avec la
 * table HEC_EVENT
 *
 * @author 11183065
 *
 */
public interface HecCourseEventSynchroJob extends Job {

    void execute(JobExecutionContext context) throws JobExecutionException;
}

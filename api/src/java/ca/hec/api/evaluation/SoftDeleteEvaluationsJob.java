package ca.hec.api.evaluation;

import org.quartz.Job;

/**
 * Job quartz used to mark all the evaluations for a given session as deleted. The evaluations will no
 * longer be available through the interface
 * 
 * @author 11091096
 *
 */
public interface SoftDeleteEvaluationsJob extends Job{

}

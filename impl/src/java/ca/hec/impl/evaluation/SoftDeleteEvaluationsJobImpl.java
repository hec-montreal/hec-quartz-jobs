package ca.hec.impl.evaluation;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.evaluation.constant.EvalConstants;
import org.sakaiproject.evaluation.logic.EvalEvaluationService;
import org.sakaiproject.evaluation.logic.EvalEvaluationSetupService;
import org.sakaiproject.evaluation.model.EvalEvaluation;
import org.springframework.transaction.annotation.Transactional;

import ca.hec.api.evaluation.SoftDeleteEvaluationsJob;
import lombok.Setter;

public class SoftDeleteEvaluationsJobImpl implements SoftDeleteEvaluationsJob{
	
	@Setter
    protected EvalEvaluationService evaluationService;

    @Setter
    protected EvalEvaluationSetupService evaluationSetupService;
    
	private static final Log LOG = LogFactory.getLog(SoftDeleteEvaluationsJobImpl.class);
	
	@Transactional
	public void execute(JobExecutionContext context) throws JobExecutionException {
	
		LOG.debug("SoftDeleteEvaluations.execute()");
 
		String termId = context.getMergedJobDataMap().getString("term");
		
        List<EvalEvaluation> evaluations = evaluationService.getEvaluationsByTermId(termId);
  
        LOG.debug("Found "+ evaluations.size() + " evaluations to delete matching " + termId);
  
        for (EvalEvaluation evaluation: evaluations) {

        	LOG.debug("Soft deleting evaluation id " + evaluation.getId());
        	evaluation.setState(EvalConstants.EVALUATION_STATE_DELETED);
        	evaluationSetupService.saveEvaluation(evaluation, "admin", false);
        }
	}


}

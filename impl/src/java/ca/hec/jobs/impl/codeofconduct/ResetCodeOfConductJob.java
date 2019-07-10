package ca.hec.jobs.impl.codeofconduct;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.transaction.annotation.Transactional;
import org.sakaiproject.db.cover.SqlService;

public class ResetCodeOfConductJob implements Job {

	private static Log log = LogFactory
			.getLog(ResetCodeOfConductJob.class);

	private SqlService sqlService;
	
	@Transactional
	@Override
	public void execute(JobExecutionContext arg0) throws JobExecutionException {
		boolean success = false;

		log.info("starting ResetCodeOfConductJob");
	
		// copy code_of_conduct to code_of_conduct_delete
		String backupDataSql = "insert into code_of_conduct_delete select * from code_of_conduct";
		success = sqlService.dbWrite(backupDataSql);

		if (success) {
			// empty code_of_conduct table
			String emptyTableSql = "delete from code_of_conduct";
			success = sqlService.dbWrite(emptyTableSql);
		}
		
		if (success) {
		    	log.info("Successfully copied and deleted code of conduct data. ");
		}
		else {
			log.error("Error removing code of conduct data.");
		}
	}
}

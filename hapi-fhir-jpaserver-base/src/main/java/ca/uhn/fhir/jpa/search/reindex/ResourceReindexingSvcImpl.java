/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.search.reindex;

import ca.uhn.fhir.batch2.model.JobInstanceStartRequest;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.dao.data.IResourceReindexJobDao;
import ca.uhn.fhir.jpa.dao.data.IResourceTableDao;
import ca.uhn.fhir.jpa.entity.ResourceReindexJobEntity;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.model.entity.EntityIndexStatusEnum;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.sched.HapiJob;
import ca.uhn.fhir.jpa.model.sched.IHasScheduledJobs;
import ca.uhn.fhir.jpa.model.sched.ISchedulerService;
import ca.uhn.fhir.jpa.model.sched.ScheduledJobDefinition;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.util.StopWatch;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceContextType;
import jakarta.persistence.Query;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.time.DateUtils;
import org.hl7.fhir.r4.model.InstantType;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * @deprecated Use the Batch2 {@link ca.uhn.fhir.batch2.api.IJobCoordinator#startInstance(JobInstanceStartRequest)} instead.
 */
@SuppressWarnings({"removal", "DeprecatedIsStillUsed"})
@Deprecated
public class ResourceReindexingSvcImpl implements IResourceReindexingSvc, IHasScheduledJobs {

	private static final Date BEGINNING_OF_TIME = new Date(0);
	private static final Logger ourLog = LoggerFactory.getLogger(ResourceReindexingSvcImpl.class);
	private static final int PASS_SIZE = 25000;
	private final ReentrantLock myIndexingLock = new ReentrantLock();

	@Autowired
	private IResourceReindexJobDao myReindexJobDao;

	@Autowired
	private JpaStorageSettings myStorageSettings;

	@Autowired
	private PlatformTransactionManager myTxManager;

	private TransactionTemplate myTxTemplate;
	private final ThreadFactory myReindexingThreadFactory =
			new BasicThreadFactory.Builder().namingPattern("ResourceReindex-%d").build();
	private ThreadPoolExecutor myTaskExecutor;

	@Autowired
	private IResourceTableDao myResourceTableDao;

	@Autowired
	private FhirContext myContext;

	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;

	@Autowired
	private ISearchParamRegistry mySearchParamRegistry;

	@Autowired
	private ResourceReindexer myResourceReindexer;

	@VisibleForTesting
	void setStorageSettingsForUnitTest(JpaStorageSettings theStorageSettings) {
		myStorageSettings = theStorageSettings;
	}

	@VisibleForTesting
	void setContextForUnitTest(FhirContext theContext) {
		myContext = theContext;
	}

	@PostConstruct
	public void start() {
		myTxTemplate = new TransactionTemplate(myTxManager);
		initExecutor();
	}

	public void initExecutor() {
		// Create the threadpool executor used for reindex jobs
		int reindexThreadCount = myStorageSettings.getReindexThreadCount();
		RejectedExecutionHandler rejectHandler = new BlockPolicy();
		myTaskExecutor = new ThreadPoolExecutor(
				0,
				reindexThreadCount,
				0L,
				TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(100),
				myReindexingThreadFactory,
				rejectHandler);
	}

	@Override
	public void scheduleJobs(ISchedulerService theSchedulerService) {
		ScheduledJobDefinition jobDetail = new ScheduledJobDefinition();
		jobDetail.setId(getClass().getName());
		jobDetail.setJobClass(Job.class);
		theSchedulerService.scheduleClusteredJob(10 * DateUtils.MILLIS_PER_SECOND, jobDetail);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public Long markAllResourcesForReindexing() {
		return markAllResourcesForReindexing(null);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public Long markAllResourcesForReindexing(String theType) {

		String typeDesc;
		if (isNotBlank(theType)) {
			try {
				myContext.getResourceType(theType);
			} catch (DataFormatException e) {
				throw new InvalidRequestException(Msg.code(1170) + "Unknown resource type: " + theType);
			}
			myReindexJobDao.markAllOfTypeAsDeleted(theType);
			typeDesc = theType;
		} else {
			myReindexJobDao.markAllOfTypeAsDeleted();
			typeDesc = "(any)";
		}

		ResourceReindexJobEntity job = new ResourceReindexJobEntity();
		job.setResourceType(theType);
		job.setThresholdHigh(DateUtils.addMinutes(new Date(), 5));
		job = myReindexJobDao.saveAndFlush(job);

		ourLog.info("Marking all resources of type {} for reindexing - Got job ID[{}]", typeDesc, job.getId());
		return job.getId();
	}

	public static class Job implements HapiJob {
		@Autowired
		private IResourceReindexingSvc myTarget;

		@Override
		public void execute(JobExecutionContext theContext) {
			myTarget.runReindexingPass();
		}
	}

	@VisibleForTesting
	ReentrantLock getIndexingLockForUnitTest() {
		return myIndexingLock;
	}

	@Override
	@Transactional(propagation = Propagation.NEVER)
	public Integer runReindexingPass() {
		if (myStorageSettings.isSchedulingDisabled() || !myStorageSettings.isEnableTaskPreExpandValueSets()) {
			return null;
		}
		if (myIndexingLock.tryLock()) {
			try {
				return doReindexingPassInsideLock();
			} finally {
				myIndexingLock.unlock();
			}
		}
		return null;
	}

	private int doReindexingPassInsideLock() {
		expungeJobsMarkedAsDeleted();
		return runReindexJobs();
	}

	@Override
	public int forceReindexingPass() {
		myIndexingLock.lock();
		try {
			return doReindexingPassInsideLock();
		} finally {
			myIndexingLock.unlock();
		}
	}

	@Override
	public void cancelAndPurgeAllJobs() {
		ourLog.info("Cancelling and purging all resource reindexing jobs");
		myIndexingLock.lock();
		try {
			myTxTemplate.execute(t -> {
				myReindexJobDao.markAllOfTypeAsDeleted();
				return null;
			});

			myTaskExecutor.shutdown();
			initExecutor();

			expungeJobsMarkedAsDeleted();
		} finally {
			myIndexingLock.unlock();
		}
	}

	private int runReindexJobs() {
		Collection<ResourceReindexJobEntity> jobs = getResourceReindexJobEntities();

		if (!jobs.isEmpty()) {
			ourLog.info("Running {} reindex jobs: {}", jobs.size(), jobs);
		} else {
			ourLog.debug("Running 0 reindex jobs");
			return 0;
		}

		int count = 0;
		for (ResourceReindexJobEntity next : jobs) {

			if (next.getThresholdLow() != null
					&& next.getThresholdLow().getTime()
							>= next.getThresholdHigh().getTime()) {
				markJobAsDeleted(next);
				continue;
			}

			count += runReindexJob(next);
		}
		return count;
	}

	@Override
	public int countReindexJobs() {
		return getResourceReindexJobEntities().size();
	}

	private Collection<ResourceReindexJobEntity> getResourceReindexJobEntities() {
		Collection<ResourceReindexJobEntity> jobs =
				myTxTemplate.execute(t -> myReindexJobDao.findAll(PageRequest.of(0, 10), false));
		assert jobs != null;
		return jobs;
	}

	private void markJobAsDeleted(ResourceReindexJobEntity theJob) {
		ourLog.info("Marking reindexing job ID[{}] as deleted", theJob.getId());
		myTxTemplate.execute(t -> {
			myReindexJobDao.markAsDeletedById(theJob.getId());
			return null;
		});
	}

	@VisibleForTesting
	public void setResourceReindexerForUnitTest(ResourceReindexer theResourceReindexer) {
		myResourceReindexer = theResourceReindexer;
	}

	private int runReindexJob(ResourceReindexJobEntity theJob) {
		if (theJob.getSuspendedUntil() != null) {
			if (theJob.getSuspendedUntil().getTime() > System.currentTimeMillis()) {
				return 0;
			}
		}

		ourLog.info("Performing reindex pass for JOB[{}]", theJob.getId());
		StopWatch sw = new StopWatch();
		AtomicInteger counter = new AtomicInteger();

		/*
		 * On the first time we run a particular reindex job, let's make sure we
		 * have the latest search parameters loaded. A common reason to
		 * be reindexing is that the search parameters have changed in some way, so
		 * this makes sure we're on the latest versions
		 */
		if (theJob.getThresholdLow() == null) {
			mySearchParamRegistry.forceRefresh();
		}

		// Calculate range
		Date low = theJob.getThresholdLow() != null ? theJob.getThresholdLow() : BEGINNING_OF_TIME;
		Date high = theJob.getThresholdHigh();

		// Query for resources within threshold
		StopWatch pageSw = new StopWatch();
		Slice<JpaPid> range = myTxTemplate.execute(t -> {
			PageRequest page = PageRequest.of(0, PASS_SIZE);
			if (isNotBlank(theJob.getResourceType())) {
				return myResourceTableDao.findIdsOfResourcesWithinUpdatedRangeOrderedFromOldest(
						page, theJob.getResourceType(), low, high);
			} else {
				return myResourceTableDao.findIdsOfResourcesWithinUpdatedRangeOrderedFromOldest(page, low, high);
			}
		});
		Validate.notNull(range);
		int count = range.getNumberOfElements();
		ourLog.info("Loaded {} resources for reindexing in {}", count, pageSw);

		// If we didn't find any results at all, mark as deleted
		if (count == 0) {
			markJobAsDeleted(theJob);
			return 0;
		}

		// Submit each resource requiring reindexing
		List<Future<Date>> futures = range.stream()
				.map(t -> myTaskExecutor.submit(new ResourceReindexingTask(t, counter)))
				.collect(Collectors.toList());

		Date latestDate = null;
		for (Future<Date> next : futures) {
			Date nextDate;
			try {
				nextDate = next.get();
			} catch (Exception e) {
				ourLog.error("Failure reindexing", e);
				Date suspendedUntil = DateUtils.addMinutes(new Date(), 1);
				myTxTemplate.execute(t -> {
					myReindexJobDao.setSuspendedUntil(suspendedUntil);
					return null;
				});
				return counter.get();
			}

			if (nextDate != null) {
				if (latestDate == null || latestDate.getTime() < nextDate.getTime()) {
					latestDate = new Date(nextDate.getTime());
				}
			}
		}

		Validate.notNull(latestDate);
		Date newLow;
		if (latestDate.getTime() == low.getTime()) {
			if (count == PASS_SIZE) {
				// Just in case we end up in some sort of infinite loop. This shouldn't happen, and couldn't really
				// happen unless there were 10000 resources with the exact same update time down to the
				// millisecond.
				ourLog.error(
						"Final pass time for reindex JOB[{}] has same ending low value: {}",
						theJob.getId(),
						latestDate);
			}

			newLow = new Date(latestDate.getTime() + 1);
		} else {
			newLow = latestDate;
		}

		myTxTemplate.execute(t -> {
			myReindexJobDao.setThresholdLow(theJob.getId(), newLow);
			Integer existingCount =
					myReindexJobDao.getReindexCount(theJob.getId()).orElse(0);
			int newCount = existingCount + counter.get();
			myReindexJobDao.setReindexCount(theJob.getId(), newCount);
			return null;
		});

		ourLog.info(
				"Completed pass of reindex JOB[{}] - Indexed {} resources in {} ({} / sec) - Have indexed until: {}",
				theJob.getId(),
				count,
				sw,
				sw.formatThroughput(count, TimeUnit.SECONDS),
				new InstantType(newLow));
		return counter.get();
	}

	private void expungeJobsMarkedAsDeleted() {
		myTxTemplate.execute(t -> {
			Collection<ResourceReindexJobEntity> toDelete = myReindexJobDao.findAll(PageRequest.of(0, 10), true);
			toDelete.forEach(job -> {
				ourLog.info("Purging deleted job[{}]", job.getId());
				myReindexJobDao.deleteById(job.getId());
			});
			return null;
		});
	}

	private void markResourceAsIndexingFailed(final JpaPid theId) {
		TransactionTemplate txTemplate = new TransactionTemplate(myTxManager);
		txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		txTemplate.execute((TransactionCallback<Void>) theStatus -> {
			ourLog.info("Marking resource with PID {} as indexing_failed", theId);

			myResourceTableDao.updateIndexStatus(theId, EntityIndexStatusEnum.INDEXING_FAILED);

			Query q = myEntityManager.createQuery("DELETE FROM ResourceTag t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery(
					"DELETE FROM ResourceIndexedSearchParamCoords t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery(
					"DELETE FROM ResourceIndexedSearchParamDate t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery(
					"DELETE FROM ResourceIndexedSearchParamNumber t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery(
					"DELETE FROM ResourceIndexedSearchParamQuantity t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery(
					"DELETE FROM ResourceIndexedSearchParamQuantityNormalized t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery(
					"DELETE FROM ResourceIndexedSearchParamString t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery(
					"DELETE FROM ResourceIndexedSearchParamToken t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery(
					"DELETE FROM ResourceIndexedSearchParamUri t WHERE t.myResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceLink t WHERE t.mySourceResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceLink t WHERE t.myTargetResource.myPid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			return null;
		});
	}

	private class ResourceReindexingTask implements Callable<Date> {
		private final JpaPid myNextId;
		private final AtomicInteger myCounter;
		private Date myUpdated;

		ResourceReindexingTask(JpaPid theNextId, AtomicInteger theCounter) {
			myNextId = theNextId;
			myCounter = theCounter;
		}

		@Override
		public Date call() {
			Throwable reindexFailure;

			try {
				reindexFailure = readResourceAndReindex();
			} catch (ResourceVersionConflictException e) {
				/*
				 * We reindex in multiple threads, so it's technically possible that two threads try
				 * to index resources that cause a constraint error now (i.e. because a unique index has been
				 * added that didn't previously exist). In this case, one of the threads would succeed and
				 * not get this error, so we'll let the other one fail and try
				 * again later.
				 */
				ourLog.info(
						"Failed to reindex because of a version conflict. Leaving in unindexed state: {}",
						e.getMessage());
				reindexFailure = null;
			}

			if (reindexFailure != null) {
				ourLog.info("Setting resource PID[{}] status to ERRORED", myNextId);
				markResourceAsIndexingFailed(myNextId);
			}

			return myUpdated;
		}

		@Nullable
		private Throwable readResourceAndReindex() {
			Throwable reindexFailure;
			reindexFailure = myTxTemplate.execute(t -> {
				ResourceTable resourceTable =
						myResourceTableDao.findById(myNextId).orElseThrow(IllegalStateException::new);
				myUpdated = resourceTable.getUpdatedDate();

				try {
					myResourceReindexer.reindexResourceEntity(resourceTable);
					myCounter.incrementAndGet();
					return null;

				} catch (Exception e) {
					ourLog.error("Failed to index resource {}: {}", resourceTable.getIdDt(), e, e);
					t.setRollbackOnly();
					return e;
				}
			});
			return reindexFailure;
		}
	}
}

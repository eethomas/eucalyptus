/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/

package com.eucalyptus.cloudwatch.backend;

import com.eucalyptus.auth.Permissions;
import com.eucalyptus.auth.principal.OwnerFullName;
import com.eucalyptus.cloudwatch.common.CloudWatchBackend;
import com.eucalyptus.cloudwatch.common.CloudWatchMetadata;
import com.eucalyptus.cloudwatch.common.backend.msgs.AlarmHistoryItem;
import com.eucalyptus.cloudwatch.common.backend.msgs.AlarmHistoryItems;
import com.eucalyptus.cloudwatch.common.backend.msgs.DeleteAlarmsResponseType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DeleteAlarmsType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DescribeAlarmHistoryResponseType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DescribeAlarmHistoryType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DescribeAlarmsForMetricResponseType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DescribeAlarmsForMetricType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DescribeAlarmsResponseType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DescribeAlarmsType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DisableAlarmActionsResponseType;
import com.eucalyptus.cloudwatch.common.backend.msgs.DisableAlarmActionsType;
import com.eucalyptus.cloudwatch.common.backend.msgs.EnableAlarmActionsResponseType;
import com.eucalyptus.cloudwatch.common.backend.msgs.EnableAlarmActionsType;
import com.eucalyptus.cloudwatch.common.backend.msgs.MetricAlarm;
import com.eucalyptus.cloudwatch.common.backend.msgs.MetricAlarms;
import com.eucalyptus.cloudwatch.common.backend.msgs.PutMetricAlarmResponseType;
import com.eucalyptus.cloudwatch.common.backend.msgs.PutMetricAlarmType;
import com.eucalyptus.cloudwatch.common.backend.msgs.SetAlarmStateResponseType;
import com.eucalyptus.cloudwatch.common.backend.msgs.SetAlarmStateType;
import com.eucalyptus.cloudwatch.common.config.CloudWatchConfigProperties;
import com.eucalyptus.cloudwatch.common.internal.domain.InvalidTokenException;
import com.eucalyptus.cloudwatch.common.internal.domain.alarms.AlarmEntity;
import com.eucalyptus.cloudwatch.common.internal.domain.alarms.AlarmEntity.ComparisonOperator;
import com.eucalyptus.cloudwatch.common.internal.domain.alarms.AlarmEntity.StateValue;
import com.eucalyptus.cloudwatch.common.internal.domain.alarms.AlarmEntity.Statistic;
import com.eucalyptus.cloudwatch.common.internal.domain.alarms.AlarmHistory;
import com.eucalyptus.cloudwatch.common.internal.domain.alarms.AlarmHistory.HistoryItemType;
import com.eucalyptus.cloudwatch.common.internal.domain.alarms.AlarmManager;
import com.eucalyptus.cloudwatch.common.internal.domain.alarms.AlarmNotFoundException;
import com.eucalyptus.cloudwatch.common.internal.domain.metricdata.Units;
import com.eucalyptus.cloudwatch.common.policy.CloudWatchPolicySpec;
import com.eucalyptus.cloudwatch.workflow.DBCleanupService;
import com.eucalyptus.cloudwatch.workflow.alarms.AlarmStateEvaluationDispatcher;
import com.eucalyptus.component.Faults;
import com.eucalyptus.context.Context;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.RestrictedTypes;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import org.apache.log4j.Logger;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CloudWatchBackendService {

  static {
    // TODO: make this configurable
    ExecutorService fixedThreadPool = Executors.newFixedThreadPool(5);
    ScheduledExecutorService alarmWorkerService = Executors
        .newSingleThreadScheduledExecutor();
    alarmWorkerService.scheduleAtFixedRate(new AlarmStateEvaluationDispatcher(
        fixedThreadPool), 0, 1, TimeUnit.MINUTES);
    ScheduledExecutorService dbCleanupService = Executors
        .newSingleThreadScheduledExecutor();
    dbCleanupService.scheduleAtFixedRate(new DBCleanupService(), 1, 24,
        TimeUnit.HOURS);
  }

  private static final Logger LOG = Logger.getLogger(CloudWatchBackendService.class);

  public PutMetricAlarmResponseType putMetricAlarm(PutMetricAlarmType request)
      throws CloudWatchException {
    PutMetricAlarmResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup();

    try {
      // IAM Action Check
      checkActionPermission(CloudWatchPolicySpec.CLOUDWATCH_PUTMETRICALARM, ctx);
      if (CloudWatchConfigProperties.isDisabledCloudWatchService()) {
        faultDisableCloudWatchServiceIfNecessary();
        throw new ServiceDisabledException("Service Disabled");
      }
      final OwnerFullName ownerFullName = ctx.getUserFullName();
      final String accountId = ownerFullName.getAccountNumber();
      final Boolean actionsEnabled = CloudWatchBackendServiceFieldValidator.validateActionsEnabled(request.getActionsEnabled(), true);
      final Map<String, String> dimensionMap = TransformationFunctions.DimensionsToMap.INSTANCE
          .apply(CloudWatchBackendServiceFieldValidator.validateDimensions(request.getDimensions()));
      final Collection<String> alarmActions = CloudWatchBackendServiceFieldValidator.validateActions(
        request.getAlarmActions(), dimensionMap, "AlarmActions");
      final String alarmDescription = CloudWatchBackendServiceFieldValidator.validateAlarmDescription(
        request.getAlarmDescription());
      final String alarmName = CloudWatchBackendServiceFieldValidator.validateAlarmName(request.getAlarmName(), true);
      final ComparisonOperator comparisonOperator = CloudWatchBackendServiceFieldValidator.validateComparisonOperator(
        request.getComparisonOperator(), true);
      Integer evaluationPeriods = CloudWatchBackendServiceFieldValidator.validateEvaluationPeriods(
        request.getEvaluationPeriods(), true);
      final Integer period = CloudWatchBackendServiceFieldValidator.validatePeriod(request.getPeriod(), true);
      CloudWatchBackendServiceFieldValidator.validatePeriodAndEvaluationPeriodsNotAcrossDays(period, evaluationPeriods);
      final Collection<String> insufficientDataActions = CloudWatchBackendServiceFieldValidator.validateActions(
        request.getInsufficientDataActions(), dimensionMap,
        "InsufficientDataActions");
      final String metricName = CloudWatchBackendServiceFieldValidator.validateMetricName(request.getMetricName(),
        true);
      final String namespace = CloudWatchBackendServiceFieldValidator.validateNamespace(request.getNamespace(), true);
      final Collection<String> okActions = CloudWatchBackendServiceFieldValidator.validateActions(
        request.getOkActions(), dimensionMap, "OKActions");
      final Statistic statistic = CloudWatchBackendServiceFieldValidator.validateStatistic(request.getStatistic(),
        true);
      final Double threshold = CloudWatchBackendServiceFieldValidator.validateThreshold(request.getThreshold(), true);
      final Units unit = CloudWatchBackendServiceFieldValidator.validateUnits(request.getUnit(), true);
      if (AlarmManager.countMetricAlarms(accountId) >= 5000) {
        throw new LimitExceededException("The maximum limit of 5000 alarms would be exceeded.");
      }
      AlarmManager.putMetricAlarm(accountId, actionsEnabled, alarmActions,
          alarmDescription, alarmName, comparisonOperator, dimensionMap,
          evaluationPeriods, insufficientDataActions, metricName,
          CloudWatchBackendServiceFieldValidator.getMetricTypeFromNamespace(namespace), namespace, okActions, period,
          statistic, threshold, unit);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public DisableAlarmActionsResponseType disableAlarmActions(
      final DisableAlarmActionsType request
  ) throws EucalyptusCloudException {
    final DisableAlarmActionsResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup();

    try {
      if (CloudWatchConfigProperties.isDisabledCloudWatchService()) {
        faultDisableCloudWatchServiceIfNecessary();
        throw new ServiceDisabledException("Service Disabled");
      }
      final OwnerFullName ownerFullName = ctx.getUserFullName();
      final String accountId = ownerFullName.getAccountNumber();
      final Collection<String> alarmNames =
          CloudWatchBackendServiceFieldValidator.validateAlarmNames(request.getAlarmNames(), true);
      if ( !AlarmManager.disableAlarmActions(
          accountId,
          alarmNames,
          RestrictedTypes.<CloudWatchMetadata.AlarmMetadata>filterPrivileged( ) ) ) {
        throw new EucalyptusCloudException("User does not have permission");
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public DescribeAlarmsResponseType describeAlarms(
      final DescribeAlarmsType request
  ) throws CloudWatchException {
    final DescribeAlarmsResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup();

    try {
      final boolean showAll = request.getAlarms().remove( "verbose" );
      final OwnerFullName ownerFullName = ctx.getUserFullName();
      final String accountId = ctx.isAdministrator() && showAll ? null : ownerFullName.getAccountNumber();
      final String actionPrefix = CloudWatchBackendServiceFieldValidator.validateActionPrefix(
        request.getActionPrefix(), false);
      final String alarmNamePrefix = CloudWatchBackendServiceFieldValidator.validateAlarmNamePrefix(
        request.getAlarmNamePrefix(), false);
      final Collection<String> alarmNames = CloudWatchBackendServiceFieldValidator.validateAlarmNames(
        request.getAlarmNames(), false);
      CloudWatchBackendServiceFieldValidator.validateNotBothAlarmNamesAndAlarmNamePrefix(alarmNames, alarmNamePrefix);
      final Integer maxRecords = CloudWatchBackendServiceFieldValidator.validateMaxRecords(request.getMaxRecords());
      final String nextToken = request.getNextToken();
      final StateValue stateValue = CloudWatchBackendServiceFieldValidator.validateStateValue(request.getStateValue(),
        false);
      final List<AlarmEntity> results;
      try {
        results = AlarmManager.describeAlarms(accountId,
          actionPrefix, alarmNamePrefix, alarmNames, maxRecords, stateValue,
          nextToken, RestrictedTypes.filteringFor(CloudWatchMetadata.AlarmMetadata.class).byPrivileges().buildPredicate());
      } catch (InvalidTokenException ex) {
        throw new InvalidNextTokenException(ex.getMessage());
      }
      if (maxRecords != null && results.size() == maxRecords) {
        reply.getDescribeAlarmsResult().setNextToken(
            results.get(results.size() - 1).getNaturalId());
      }
      final MetricAlarms metricAlarms = new MetricAlarms();
      metricAlarms.setMember(Lists.newArrayList(Collections2
          .<AlarmEntity, MetricAlarm> transform(results,
              TransformationFunctions.AlarmEntityToMetricAlarm.INSTANCE)));
      reply.getDescribeAlarmsResult().setMetricAlarms(metricAlarms);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public DescribeAlarmsForMetricResponseType describeAlarmsForMetric(
    final DescribeAlarmsForMetricType request
  ) throws CloudWatchException {
    final DescribeAlarmsForMetricResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup();

    try {
      final OwnerFullName ownerFullName = ctx.getUserFullName();
      final String accountId = ownerFullName.getAccountNumber();
      final Map<String, String> dimensionMap = TransformationFunctions.DimensionsToMap.INSTANCE
          .apply(CloudWatchBackendServiceFieldValidator.validateDimensions(request.getDimensions()));
      final String metricName = CloudWatchBackendServiceFieldValidator.validateMetricName(request.getMetricName(),
        true);
      final String namespace = CloudWatchBackendServiceFieldValidator.validateNamespace(request.getNamespace(), true);
      final Integer period = CloudWatchBackendServiceFieldValidator.validatePeriod(request.getPeriod(), false);
      final Statistic statistic = CloudWatchBackendServiceFieldValidator.validateStatistic(request.getStatistic(),
        false);
      final Units unit = CloudWatchBackendServiceFieldValidator.validateUnits(request.getUnit(), true);
      final Collection<AlarmEntity> results = AlarmManager.describeAlarmsForMetric(
          accountId, dimensionMap, metricName, namespace, period, statistic,
          unit, RestrictedTypes.filteringFor( CloudWatchMetadata.AlarmMetadata.class ).byPrivileges( ).buildPredicate( ) );
      final MetricAlarms metricAlarms = new MetricAlarms();
      metricAlarms.setMember(Lists.newArrayList(Collections2
          .<AlarmEntity, MetricAlarm> transform(results,
              TransformationFunctions.AlarmEntityToMetricAlarm.INSTANCE)));
      reply.getDescribeAlarmsForMetricResult().setMetricAlarms(metricAlarms);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public DescribeAlarmHistoryResponseType describeAlarmHistory(
      final DescribeAlarmHistoryType request
  ) throws CloudWatchException {
    final DescribeAlarmHistoryResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup();

    try {
      final OwnerFullName ownerFullName = ctx.getUserFullName();
      final String accountId = ownerFullName.getAccountNumber();
      final String alarmName = CloudWatchBackendServiceFieldValidator.validateAlarmName(request.getAlarmName(), false);
      final Date endDate = CloudWatchBackendServiceFieldValidator.validateEndDate(request.getEndDate(), false);
      final Date startDate = CloudWatchBackendServiceFieldValidator.validateStartDate(request.getStartDate(), false);
      CloudWatchBackendServiceFieldValidator.validateDateOrder(startDate, endDate, "StartDate", "EndDate", false,
        false);
      final HistoryItemType historyItemType = CloudWatchBackendServiceFieldValidator.validateHistoryItemType(
        request.getHistoryItemType(), false);
      final Integer maxRecords = CloudWatchBackendServiceFieldValidator.validateMaxRecords(request.getMaxRecords());
      final String nextToken = request.getNextToken();
      final List<AlarmHistory> results;
      try {
        results = AlarmManager.describeAlarmHistory(
          accountId, alarmName, endDate, historyItemType,
          maxRecords, startDate, nextToken, Predicates.compose(
            RestrictedTypes.filteringFor(CloudWatchMetadata.AlarmMetadata.class).byPrivileges().buildPredicate(),
            TransformationFunctions.AlarmHistoryToAlarmMetadata.INSTANCE));
      } catch (InvalidTokenException e) {
        throw new InvalidNextTokenException(e.getMessage());
      }
      if (maxRecords != null && results.size() == maxRecords) {
        reply.getDescribeAlarmHistoryResult().setNextToken(
            results.get(results.size() - 1).getNaturalId());
      }
      final AlarmHistoryItems alarmHistoryItems = new AlarmHistoryItems();
      alarmHistoryItems
          .setMember(Lists.newArrayList(Collections2
              .<AlarmHistory, AlarmHistoryItem> transform(
                  results,
                  TransformationFunctions.AlarmHistoryToAlarmHistoryItem.INSTANCE)));
      reply.getDescribeAlarmHistoryResult().setAlarmHistoryItems(
          alarmHistoryItems);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public EnableAlarmActionsResponseType enableAlarmActions(
      EnableAlarmActionsType request) throws CloudWatchException {
    final EnableAlarmActionsResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup();

    try {
      if (CloudWatchConfigProperties.isDisabledCloudWatchService()) {
        faultDisableCloudWatchServiceIfNecessary();
        throw new ServiceDisabledException("Service Disabled");
      }

      final OwnerFullName ownerFullName = ctx.getUserFullName();
      final String accountId = ownerFullName.getAccountNumber();
      final Collection<String> alarmNames =
          CloudWatchBackendServiceFieldValidator.validateAlarmNames(request.getAlarmNames(), true);
      if ( !AlarmManager.enableAlarmActions(
          accountId,
          alarmNames,
          RestrictedTypes.<CloudWatchMetadata.AlarmMetadata>filterPrivileged( )) ) {
        throw new EucalyptusCloudException("User does not have permission");
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public DeleteAlarmsResponseType deleteAlarms(DeleteAlarmsType request)
      throws CloudWatchException {
    DeleteAlarmsResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup();
    try {
      if (CloudWatchConfigProperties.isDisabledCloudWatchService()) {
        faultDisableCloudWatchServiceIfNecessary();
        throw new ServiceDisabledException("Service Disabled");
      }

      final OwnerFullName ownerFullName = ctx.getUserFullName();
      final String accountId = ownerFullName.getAccountNumber();
      final Collection<String> alarmNames =
          CloudWatchBackendServiceFieldValidator.validateAlarmNames(request.getAlarmNames(), true);
      if ( !AlarmManager.deleteAlarms(
          accountId,
          alarmNames,
          RestrictedTypes.<CloudWatchMetadata.AlarmMetadata>filterPrivileged( ) ) ) {
        throw new EucalyptusCloudException("User does not have permission");
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public SetAlarmStateResponseType setAlarmState(SetAlarmStateType request)
      throws CloudWatchException {
    final SetAlarmStateResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup();
    try {
      if (CloudWatchConfigProperties.isDisabledCloudWatchService()) {
        faultDisableCloudWatchServiceIfNecessary();
        throw new ServiceDisabledException("Service Disabled");
      }
      final OwnerFullName ownerFullName = ctx.getUserFullName();
      final String accountId = ownerFullName.getAccountNumber();
      final String alarmName = CloudWatchBackendServiceFieldValidator.validateAlarmName(request.getAlarmName(), true);
      final String stateReason = CloudWatchBackendServiceFieldValidator.validateStateReason(request.getStateReason(), true);
      final String stateReasonData = CloudWatchBackendServiceFieldValidator.validateStateReasonData(request.getStateReasonData(), false);
      final StateValue stateValue = CloudWatchBackendServiceFieldValidator.validateStateValue(request.getStateValue(), true);
      try {
        AlarmManager.setAlarmState(
            accountId, alarmName, stateReason, stateReasonData, stateValue,
            RestrictedTypes.<CloudWatchMetadata.AlarmMetadata>filterPrivileged( ) );
      } catch (AlarmNotFoundException ex) {
        throw new ResourceNotFoundException(ex.getMessage());
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }
  private static final int DISABLED_SERVICE_FAULT_ID = 1500;
  private boolean alreadyFaulted = false;
  private void faultDisableCloudWatchServiceIfNecessary() {
    // TODO Auto-generated method stub
    if (!alreadyFaulted) {
      Faults.forComponent(CloudWatchBackend.class).havingId(DISABLED_SERVICE_FAULT_ID).withVar("component", "cloudwatch").log();
      alreadyFaulted = true;
    }
    
  }


  private void checkActionPermission(final String actionType, final Context ctx)
      throws EucalyptusCloudException {
    if (!Permissions.isAuthorized(CloudWatchPolicySpec.VENDOR_CLOUDWATCH, actionType, "",
        ctx.getAccount(), actionType, ctx.getAuthContext())) {
      throw new EucalyptusCloudException("User does not have permission");
    }
  }

  private static void handleException(final Exception e)
      throws CloudWatchException {
    final CloudWatchException cause = Exceptions.findCause(e,
        CloudWatchException.class);
    if (cause != null) {
      throw cause;
    }

    final InternalFailureException exception = new InternalFailureException(
        String.valueOf(e.getMessage()));
    if (Contexts.lookup().hasAdministrativePrivileges()) {
      exception.initCause(e);
    }
    throw exception;
  }

}

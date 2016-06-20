package com.linkedin.datastream.server.dms;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;

import com.linkedin.data.template.StringMap;
import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamAlreadyExistsException;
import com.linkedin.datastream.common.DatastreamMetadataConstants;
import com.linkedin.datastream.common.RestliUtils;
import com.linkedin.datastream.server.Coordinator;
import com.linkedin.datastream.server.DatastreamServer;
import com.linkedin.datastream.server.api.connector.DatastreamValidationException;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.server.CreateResponse;
import com.linkedin.restli.server.PagingContext;
import com.linkedin.restli.server.UpdateResponse;
import com.linkedin.restli.server.annotations.Context;
import com.linkedin.restli.server.annotations.RestLiCollection;
import com.linkedin.restli.server.resources.CollectionResourceTemplate;


/*
 * Resources classes are used by rest.li to process corresponding http request.
 * Note that rest.li will instantiate an object each time it processes a request.
 * So do make it thread-safe when implementing the resources.
 */
@RestLiCollection(name = "datastream", namespace = "com.linkedin.datastream.server.dms")
public class DatastreamResources extends CollectionResourceTemplate<String, Datastream> {
  private static final Logger LOG = LoggerFactory.getLogger(DatastreamResources.class);
  private static final String CLASS_NAME = DatastreamResources.class.getSimpleName();

  private final DatastreamStore _store;
  private final Coordinator _coordinator;
  private final ErrorLogger _errorLogger;

  private static final Counter UPDATE_CALL = new Counter();
  private static final Counter DELETE_CALL = new Counter();
  private static final Counter GET_CALL = new Counter();
  private static final Counter GET_ALL_CALL = new Counter();
  private static final Counter CREATE_CALL = new Counter();
  private static final Counter CALL_ERROR = new Counter();

  private static final Histogram CREATE_CALL_LATENCY = new Histogram(new ExponentiallyDecayingReservoir());
  private static final Histogram DELETE_CALL_LATENCY = new Histogram(new ExponentiallyDecayingReservoir());

  public DatastreamResources(DatastreamServer datastreamServer) {
    _store = datastreamServer.getDatastreamStore();
    _coordinator = datastreamServer.getCoordinator();
    _errorLogger = new ErrorLogger(LOG);
  }

  @Override
  public UpdateResponse update(String key, Datastream datastream) {
    UPDATE_CALL.inc();
    // TODO: behavior of updating a datastream is not fully defined yet; block this method for now
    return new UpdateResponse(HttpStatus.S_405_METHOD_NOT_ALLOWED);
  }

  @Override
  public UpdateResponse delete(String key) {
    try {
      LOG.info("Delete datastream called for datastream " + key);
      DELETE_CALL.inc();
      long startTime = System.currentTimeMillis();
      _store.deleteDatastream(key);
      DELETE_CALL_LATENCY.update(System.currentTimeMillis() - startTime);
      return new UpdateResponse(HttpStatus.S_200_OK);
    } catch (Exception e) {
      CALL_ERROR.inc();
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
          "delete datastream failed for datastream: " + key, e);
    }

    return null;
  }

  // Returning null will automatically trigger a 404 Not Found response
  @Override
  public Datastream get(String name) {
    try {
      LOG.info(String.format("Get datastream called for datastream %s", name));
      GET_CALL.inc();
      return _store.getDatastream(name);
    } catch (Exception e) {
      CALL_ERROR.inc();
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
          "Get datastream failed for datastream: " + name, e);
    }

    return null;
  }

  @SuppressWarnings("deprecated")
  @Override
  public List<Datastream> getAll(@Context PagingContext pagingContext) {
    try {
      LOG.info(String.format("Get all datastreams called with paging context %s", pagingContext));
      GET_ALL_CALL.inc();
      return RestliUtils.withPaging(_store.getAllDatastreams(), pagingContext)
          .map(_store::getDatastream)
          .filter(stream -> stream != null)
          .collect(Collectors.toList());
    } catch (Exception e) {
      CALL_ERROR.inc();
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
          "Get all datastreams failed.", e);
    }

    return Collections.emptyList();
  }

  @Override
  public CreateResponse create(Datastream datastream) {
    try {
      LOG.info(String.format("Create datastream called with datastream %s", datastream));
      CREATE_CALL.inc();

      // rest.li has done this mandatory field check in the latest version.
      // Just in case we roll back to an earlier version, let's do the validation here anyway
      if (!datastream.hasName()) {
        CALL_ERROR.inc();
        return _errorLogger.logAndGetResponse(HttpStatus.S_400_BAD_REQUEST, "Must specify name of Datastream!");
      }
      if (!datastream.hasConnectorType()) {
        CALL_ERROR.inc();
        return _errorLogger.logAndGetResponse(HttpStatus.S_400_BAD_REQUEST, "Must specify connectorType!");
      }
      if (!datastream.hasSource()) {
        CALL_ERROR.inc();
        return _errorLogger.logAndGetResponse(HttpStatus.S_400_BAD_REQUEST, "Must specify source of Datastream!");
      }

      if (!datastream.hasMetadata()) {
        datastream.setMetadata(new StringMap());
      }

      if (datastream.hasDestination() && datastream.getDestination().hasConnectionString()) {
        datastream.getMetadata().put(DatastreamMetadataConstants.IS_USER_MANAGED_DESTINATION_KEY, "true");
      }

      long startTime = System.currentTimeMillis();

      try {
        _coordinator.initializeDatastream(datastream);
      } catch (DatastreamValidationException e) {
        CALL_ERROR.inc();
        return _errorLogger.logAndGetResponse(HttpStatus.S_400_BAD_REQUEST, "Failed to initialize Datastream: ", e);
      }

      try {
        _store.createDatastream(datastream.getName(), datastream);
      } catch (DatastreamAlreadyExistsException e) {
        CALL_ERROR.inc();
        return _errorLogger.logAndGetResponse(HttpStatus.S_409_CONFLICT, "Failed to create datastream: " + datastream,
            e);
      }

      CREATE_CALL_LATENCY.update(System.currentTimeMillis() - startTime);

      return new CreateResponse(datastream.getName(), HttpStatus.S_201_CREATED);
    } catch (Exception e) {
      CALL_ERROR.inc();
      return _errorLogger.logAndGetResponse(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
          "Failed to create datastream: " + datastream, e);
    }
  }

  public static Map<String, Metric> getMetrics() {
    Map<String, Metric> metrics = new HashMap<>();

    metrics.put(MetricRegistry.name(CLASS_NAME, "updateCall"), UPDATE_CALL);
    metrics.put(MetricRegistry.name(CLASS_NAME, "deleteCall"), DELETE_CALL);
    metrics.put(MetricRegistry.name(CLASS_NAME, "getCall"), GET_CALL);
    metrics.put(MetricRegistry.name(CLASS_NAME, "getAllCall"), GET_ALL_CALL);
    metrics.put(MetricRegistry.name(CLASS_NAME, "createCall"), CREATE_CALL);
    metrics.put(MetricRegistry.name(CLASS_NAME, "callError"), CALL_ERROR);

    metrics.put(MetricRegistry.name(CLASS_NAME, "createCallLatency"), CREATE_CALL_LATENCY);
    metrics.put(MetricRegistry.name(CLASS_NAME, "deleteCallLatency"), DELETE_CALL_LATENCY);

    return Collections.unmodifiableMap(metrics);
  }
}

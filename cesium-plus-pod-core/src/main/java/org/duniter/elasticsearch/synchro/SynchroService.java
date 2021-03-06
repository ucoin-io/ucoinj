package org.duniter.elasticsearch.synchro;

/*
 * #%L
 * Duniter4j :: ElasticSearch Plugin
 * %%
 * Copyright (C) 2014 - 2016 EIS
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import com.google.common.collect.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.duniter.core.client.dao.CurrencyDao;
import org.duniter.core.client.model.bma.EndpointApi;
import org.duniter.core.client.model.local.Peer;
import org.duniter.core.client.service.HttpService;
import org.duniter.core.service.CryptoService;
import org.duniter.core.util.CollectionUtils;
import org.duniter.core.util.DateUtils;
import org.duniter.core.util.Preconditions;
import org.duniter.core.util.StringUtils;
import org.duniter.core.util.websocket.WebsocketClientEndpoint;
import org.duniter.elasticsearch.PluginSettings;
import org.duniter.elasticsearch.client.Duniter4jClient;
import org.duniter.elasticsearch.dao.SynchroExecutionDao;
import org.duniter.elasticsearch.model.SynchroExecution;
import org.duniter.elasticsearch.model.SynchroResult;
import org.duniter.elasticsearch.service.AbstractService;
import org.duniter.elasticsearch.service.NetworkService;
import org.duniter.elasticsearch.service.ServiceLocator;
import org.duniter.elasticsearch.service.changes.ChangeEvent;
import org.duniter.elasticsearch.service.changes.ChangeEvents;
import org.duniter.elasticsearch.service.changes.ChangeSource;
import org.duniter.elasticsearch.threadpool.ScheduledActionFuture;
import org.duniter.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.common.inject.Inject;

import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by blavenie on 27/10/16.
 */
public class SynchroService extends AbstractService {

    private static final String WS_CHANGES_URL = "/ws/_changes";
    private final static Set<String> includeEndpointApis = Sets.newHashSet();
    private static List<WebsocketClientEndpoint> wsClientEndpoints = Lists.newCopyOnWriteArrayList();
    private static List<SynchroAction> actions = Lists.newCopyOnWriteArrayList();

    private HttpService httpService;
    private final ThreadPool threadPool;
    private final CurrencyDao currencyDao;
    private final SynchroExecutionDao synchroExecutionDao;
    private final NetworkService networkService;

    private boolean forceFullResync = false;
    private boolean synchronizing = false;

    @Inject
    public SynchroService(Duniter4jClient client,
                          PluginSettings settings,
                          CryptoService cryptoService,
                          ThreadPool threadPool,
                          CurrencyDao currencyDao,
                          SynchroExecutionDao synchroExecutionDao,
                          NetworkService networkService,
                          final ServiceLocator serviceLocator) {
        super("duniter.p2p", client, settings, cryptoService);
        this.threadPool = threadPool;
        this.currencyDao = currencyDao;
        this.synchroExecutionDao = synchroExecutionDao;
        this.networkService = networkService;
        threadPool.scheduleOnStarted(() -> {
            httpService = serviceLocator.getHttpService();
            setIsReady(true);
        });
    }

    public void register(SynchroAction action) {
        Preconditions.checkNotNull(action);
        Preconditions.checkNotNull(action.getEndPointApi());

        if (!includeEndpointApis.contains(action.getEndPointApi())) {
            includeEndpointApis.add(action.getEndPointApi());
        }
        actions.add(action);
    }

    /**
     * Start scheduling doc stats update
     * @return
     */
    public ScheduledActionFuture<?> startScheduling() {

        final ScheduledActionFuture future = new ScheduledActionFuture(null);

        // Launch once
        future.setDelegate(threadPool.scheduleOnClusterReady(() -> {
            boolean launchAtStartup;
            try {
                // wait for some peers
                launchAtStartup = networkService.waitPeersReady(includeEndpointApis);
            } catch (InterruptedException e) {
                return; // stop
            }

            // If can be launched now: do it
            if (launchAtStartup) {

                forceFullResync = pluginSettings.fullResyncAtStartup();

                // Apply a safe synchro, to be sure new scheduleAtFixedRate() will be called, if failed
                safeSynchronize();

                forceFullResync = false;
            }

            // Schedule next execution, to 5 min before each hour
            // (to make sure to be ready when computing doc stat - see DocStatService)
            long nextExecutionDelay = DateUtils.nextHour().getTime() - System.currentTimeMillis() - 5 * 60 * 1000;

            // If next execution is too close, skip it
            if (launchAtStartup && nextExecutionDelay < 5 * 60 * 1000) {
                // add an hour
                nextExecutionDelay += 60 * 60 * 1000;
            }

            // Schedule every hour
            future.setDelegate(threadPool.scheduleAtFixedRate(
                    this::safeSynchronize,
                    nextExecutionDelay,
                    60 * 60 * 1000 /* every hour */,
                    TimeUnit.MILLISECONDS));

        }));

        return future;
    }

    public void safeSynchronize() {
        if (synchronizing) {
            logger.warn("Previous synchronization still running. Skipping execution.");
            return;
        }

        // Can only run once
        synchronized(this) {
            try {

                synchronizing = true;

                synchronize();
            }
            catch(Throwable e) {
                logger.error(String.format("Failed to execute synchronization: %s", e.getMessage()), e);
                // Continue
            }
            finally {
                synchronizing = false;
            }
        }
    }

    public void synchronize() {

        final boolean enableSynchroWebsocket = pluginSettings.enableSynchroWebsocket();

        // Closing all opened WS
        if (enableSynchroWebsocket) {
            closeWsClientEndpoints();
        }

        Set<String> currencyIds;
        try {
            currencyIds = currencyDao.getAllIds();
        }
        catch (Exception e) {
            logger.error("Could not load indexed currencies", e);
            currencyIds = null;
        }

        if (CollectionUtils.isEmpty(currencyIds) || CollectionUtils.isEmpty(includeEndpointApis)) {
            logger.warn("Skipping synchronization: no indexed currency or no API configured");
            return;
        }

        // Collect all hash of cluster endpoints
        Peer clusterPeer = pluginSettings.getClusterPeer().orElse(null);

        currencyIds.forEach(currencyId -> includeEndpointApis.forEach(endpointApi -> {

            String logPrefix = String.format("[%s] [%s]", currencyId, endpointApi);
            long now = System.currentTimeMillis();

            logger.info(String.format("%s Synchronization... {peers discovery: %s}", logPrefix, pluginSettings.enableSynchroDiscovery()));

            // Get peers for this currency and current API
            Collection<Peer> peers = networkService.getPeersFromApi(currencyId, endpointApi);

            // Exclude peers when equals to the current cluster
            if (CollectionUtils.isNotEmpty(peers) && clusterPeer != null) {
                peers = peers.stream().filter(p -> !pluginSettings.sameAsClusterPeer(p))
                        .collect(Collectors.toList());
            }

            // If full resync (= only possible at startup), then use only one peer (the first one, usually from the configuration)
            if (forceFullResync && CollectionUtils.isNotEmpty(peers)) {
                Peer firstPeer = peers.iterator().next();
                peers = ImmutableList.of(firstPeer);
                logger.debug(String.format("%s Full synchronization will be limited to first peer found {%s}", logPrefix, firstPeer));
            }

            if (CollectionUtils.isEmpty(peers)) {
                logger.info(String.format("%s Synchronization [OK] - no UP peer found", logPrefix));
            }
            else {
                final MutableInt counter = new MutableInt(0);

                // Execute the synchronization, on each peer
                peers.forEach(peer -> {
                    // Check if peer alive and valid
                    boolean isAliveAndValid = networkService.isEsNodeAliveAndValid(peer);
                    if (!isAliveAndValid) {
                        logger.warn(String.format("[%s] [%s] Not reachable, or running on another currency. Skipping.", peer.getCurrency(), peer));
                        return;
                    }

                    if (StringUtils.isBlank(peer.getPubkey())) {
                        logger.warn(String.format("%s Failed to synchronize {%s}: missing pubkey", logPrefix, peer));
                    }
                    else {
                        if (peer.getHash() == null || peer.getId() == null) {
                            String hash = cryptoService.hash(peer.computeKey());
                            peer.setHash(hash);
                            peer.setId(hash);
                            if (logger.isDebugEnabled()) {
                                logger.debug(String.format("%s Computing missing hash for {%s}: %s", logPrefix, peer, hash));
                            }
                        }
                        try {
                            synchronizePeer(peer, enableSynchroWebsocket);
                            counter.increment();
                        } catch (Throwable t) {
                            logger.error(String.format("%s Failed to synchronize {%s}: %s", logPrefix, peer, t.getMessage()), t);
                        }
                    }
                });

                logger.info(String.format("%s Synchronization [OK] - %s/%s peers in %s ms", logPrefix,
                        counter.intValue(),
                        CollectionUtils.size(peers),
                        System.currentTimeMillis() - now));
            }

        }));
    }

    public SynchroResult synchronizePeer(final Peer peer, boolean enableSynchroWebsocket) {
        Preconditions.checkNotNull(peer);
        Preconditions.checkNotNull(peer.getCurrency());
        Preconditions.checkNotNull(peer.getId());
        Preconditions.checkNotNull(peer.getApi());

        long startTimeMs = System.currentTimeMillis();

        SynchroResult result = new SynchroResult();

        // Get the last execution time (or 0 is never synchronized)
        // If not the first synchro, add a delay to last execution time
        // to avoid missing data because incorrect clock configuration
        long lastExecutionTime = forceFullResync ? 0 : getLastExecutionTime(peer);
        if (logger.isDebugEnabled() && lastExecutionTime > 0) {
            logger.debug(String.format("[%s] [%s] Found last synchronization execution at {%s}. Will apply time offset of {-%s ms}", peer.getCurrency(), peer,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                            .format(new Date(lastExecutionTime * 1000)),
                    pluginSettings.getSynchroTimeOffset()));
        }

        final long fromTime = lastExecutionTime > 0 ? lastExecutionTime - pluginSettings.getSynchroTimeOffset() : 0;


        if (logger.isInfoEnabled()) {
            if (fromTime == 0) {
                logger.info(String.format("[%s] [%s] Synchronizing... {full}", peer.getCurrency(), peer));
            }
            else {
                logger.info(String.format("[%s] [%s] Synchronizing... {delta since %s}",
                        peer.getCurrency(),
                        peer,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                                .format(new Date(fromTime * 1000))));
            }
        }

        // Execute actions
        MutableInt failureCounter = new MutableInt(0);
        List<SynchroAction> executedActions = actions.stream()
                // Filter on the expected api
                .filter(a -> a.getEndPointApi() != null && a.getEndPointApi().equals(peer.getApi()))
                // Sort by execution order
                .sorted(SynchroAction.EXECUTION_ORDER_COMPARATOR)
                .map(a -> {
                    try {
                        a.handleSynchronize(peer, fromTime, result);
                    } catch(Throwable e) {
                        // Count, by continue
                        failureCounter.increment();
                    }
                    return a;
                })
                .collect(Collectors.toList());

        long executionTimeMs = System.currentTimeMillis() - startTimeMs;
        logger.info(String.format("[%s] [%s] Synchronizing [OK] - %s %s in %s ms",
                peer.getCurrency(),
                peer,
                result.toString(),
                (failureCounter.getValue() > 0 ? String.format("and %s actions in failure", failureCounter.getValue()) : ""),
                executionTimeMs));

        // Save result
        saveExecution(peer, result, startTimeMs, executionTimeMs);

        // Start listen changes on this peer
        if (enableSynchroWebsocket) {
            startListenChangesOnPeer(peer, executedActions);
        }

        return result;
    }

    /* -- protected methods -- */

    protected long getLastExecutionTime(Peer peer) {
        Preconditions.checkNotNull(peer);

        try {
            SynchroExecution execution = synchroExecutionDao.getLastExecution(peer);
            return execution != null ? execution.getTime() : 0;
        }
        catch (Exception e) {
            logger.error(String.format("Error while saving last synchro execution time, for peer [%s]. Will resync all.", peer), e);
            return 0;
        }
    }

    protected void saveExecution(Peer peer, SynchroResult result,
                                 long startTimeMs,
                                 long executionTimeMs) {
        Preconditions.checkNotNull(peer);
        Preconditions.checkNotNull(peer.getCurrency());
        Preconditions.checkNotNull(peer.getId());
        Preconditions.checkNotNull(peer.getApi());
        Preconditions.checkNotNull(result);

        // Compute hash, when missing
        String hash = peer.getHash();
        if (StringUtils.isBlank(hash)) {
            hash = cryptoService.hash(peer.computeKey());
            peer.setHash(hash);
            peer.setId(hash);
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("[%s] [%s] Computing missing hash for {%s}: %s", peer.getCurrency(), peer.getApi(), peer, hash));
            }
        }

        try {
            SynchroExecution execution = new SynchroExecution();
            execution.setCurrency(peer.getCurrency());
            execution.setPeer(hash);
            execution.setApi(peer.getApi());
            execution.setExecutionTime(executionTimeMs);
            execution.setResult(result);

            // Start execution time (in seconds)
            execution.setTime(startTimeMs/1000);

            synchroExecutionDao.save(execution);
        }
        catch (Exception e) {
            logger.error(String.format("Error while saving synchro execution on peer [%s]", peer), e);
        }
    }

    protected void closeWsClientEndpoints() {
        synchronized(wsClientEndpoints) {
            // Closing all opened WS
            wsClientEndpoints.forEach(IOUtils::closeQuietly);
            wsClientEndpoints.clear();
        }
    }

    protected void startListenChangesOnPeer(final Peer peer,
                                            final List<SynchroAction> actions) {
        // Listens changes on this peer
        Preconditions.checkNotNull(peer);
        Preconditions.checkNotNull(actions);

        // Compute a change source for ALL indices/types
        final ChangeSource changeSource = new ChangeSource();
        actions.stream()
                .map(SynchroAction::getChangeSource)
                .filter(Objects::nonNull)
                .forEach(changeSource::merge);

        // Prepare a map of actions by index/type
        final ArrayListMultimap<String, SynchroAction> actionsBySource = ArrayListMultimap.create(actions.size(), 2);
        actions.stream()
                .filter(a -> a.getChangeSource() != null)
                .forEach(a -> actionsBySource.put(a.getChangeSource().toString(), a));

        // Get (or create) the websocket endpoint
        WebsocketClientEndpoint wsClientEndPoint = httpService.getWebsocketClientEndpoint(peer, WS_CHANGES_URL, false);

        // filter on selected sources
        wsClientEndPoint.sendMessage(changeSource.toString());

        // add listener
        wsClientEndPoint.registerListener( message -> {
            try {
                ChangeEvent changeEvent = ChangeEvents.fromJson(getObjectMapper(), message);
                String source = changeEvent.getIndex() + "/" +  changeEvent.getType();
                List<SynchroAction> sourceActions = actionsBySource.get(source);

                // Call each mapped actions
                if (CollectionUtils.isNotEmpty(sourceActions)) {
                    sourceActions.forEach(a -> a.handleChange(peer, changeEvent));
                }

            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.warn(String.format("[%s] Unable to process changes received by [/ws/_changes]: %s", peer, e.getMessage()), e);
                }
                else {
                    logger.warn(String.format("[%s] Unable to process changes received by [/ws/_changes]: %s", peer, e.getMessage()));
                }
            }
        });

        // Add to list
        synchronized(wsClientEndpoints) {
            wsClientEndpoints.add(wsClientEndPoint);
        }
    }


}

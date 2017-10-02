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

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.duniter.core.client.dao.CurrencyDao;
import org.duniter.core.client.dao.PeerDao;
import org.duniter.core.client.model.bma.BlockchainBlock;
import org.duniter.core.client.model.bma.EndpointApi;
import org.duniter.core.client.model.bma.Endpoints;
import org.duniter.core.client.model.bma.NetworkPeering;
import org.duniter.core.client.model.local.Currency;
import org.duniter.core.client.model.local.Peer;
import org.duniter.core.client.service.HttpService;
import org.duniter.core.client.service.local.NetworkService;
import org.duniter.core.service.CryptoService;
import org.duniter.core.util.CollectionUtils;
import org.duniter.core.util.DateUtils;
import org.duniter.core.util.Preconditions;
import org.duniter.core.util.websocket.WebsocketClientEndpoint;
import org.duniter.elasticsearch.PluginSettings;
import org.duniter.elasticsearch.client.Duniter4jClient;
import org.duniter.elasticsearch.dao.SynchroExecutionDao;
import org.duniter.elasticsearch.model.SynchroExecution;
import org.duniter.elasticsearch.model.SynchroResult;
import org.duniter.elasticsearch.service.AbstractService;
import org.duniter.elasticsearch.service.ServiceLocator;
import org.duniter.elasticsearch.service.changes.ChangeEvent;
import org.duniter.elasticsearch.service.changes.ChangeEvents;
import org.duniter.elasticsearch.service.changes.ChangeSource;
import org.duniter.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.common.inject.Inject;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by blavenie on 27/10/16.
 */
public class SynchroService extends AbstractService {

    private static final String WS_CHANGES_URL = "/ws/_changes";

    private HttpService httpService;
    private NetworkService networkService;
    private final Set<EndpointApi> peerApiFilters = Sets.newHashSet();
    private final ThreadPool threadPool;
    private final PeerDao peerDao;
    private final CurrencyDao currencyDao;
    private final SynchroExecutionDao synchroExecutionDao;
    private List<WebsocketClientEndpoint> wsClientEndpoints = Lists.newArrayList();
    private List<SynchroAction> actions = Lists.newArrayList();

    @Inject
    public SynchroService(Duniter4jClient client,
                          PluginSettings settings,
                          CryptoService cryptoService,
                          ThreadPool threadPool,
                          CurrencyDao currencyDao,
                          PeerDao peerDao,
                          SynchroExecutionDao synchroExecutionDao,
                          final ServiceLocator serviceLocator) {
        super("duniter.p2p", client, settings, cryptoService);
        this.threadPool = threadPool;
        this.currencyDao = currencyDao;
        this.peerDao = peerDao;
        this.synchroExecutionDao = synchroExecutionDao;
        threadPool.scheduleOnStarted(() -> {
            httpService = serviceLocator.getHttpService();
            networkService = serviceLocator.getNetworkService();
            setIsReady(true);
        });
    }

    public void register(SynchroAction action) {
        Preconditions.checkNotNull(action);
        Preconditions.checkNotNull(action.getEndPointApi());

        if (!peerApiFilters.contains(action.getEndPointApi())) {
            peerApiFilters.add(action.getEndPointApi());
        }
        actions.add(action);
    }

    /**
     * Start scheduling doc stats update
     * @return
     */
    public SynchroService startScheduling() {
        // Launch once, at startup (after a delay of 10s)
        threadPool.schedule(() -> {
            boolean launchAtStartup;
            try {
                // wait for some peers
                launchAtStartup = waitPeersReady();
            } catch (InterruptedException e) {
                return; // stop
            }

            // If can be launched now: do it
            if (launchAtStartup) {
                synchronize();
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
            threadPool.scheduleAtFixedRate(
                    this::synchronize,
                    nextExecutionDelay,
                    60 * 60 * 1000 /* every hour */,
                    TimeUnit.MILLISECONDS);
        },
        10 * 1000 /*wait 10 s */ ,
        TimeUnit.MILLISECONDS);

        return this;
    }

    public void synchronize() {
        logger.info("Starting synchronization...");

        final boolean enableSynchroWebsocket = pluginSettings.enableSynchroWebsocket();

        // Closing all opened WS
        if (enableSynchroWebsocket) {
            closeWsClientEndpoints();
        }

        if (CollectionUtils.isNotEmpty(peerApiFilters)) {

            peerApiFilters.forEach(peerApiFilter -> {

                // Get peers
                List<Peer> peers = getPeersFromApi(peerApiFilter);
                if (CollectionUtils.isNotEmpty(peers)) {
                    peers.forEach(p -> synchronizePeer(p, enableSynchroWebsocket));
                    logger.info("Synchronization [OK]");
                } else {
                    logger.info(String.format("Synchronization [OK] - no endpoint found for API [%s]", peerApiFilter.name()));
                }
            });
        }
    }

    public SynchroResult synchronizePeer(final Peer peer, boolean enableSynchroWebsocket) {
        long now = System.currentTimeMillis();

        // Check if peer alive and valid
        boolean isAliveAndValid = isAliveAndValid(peer);
        if (!isAliveAndValid) {
            logger.warn(String.format("[%s] [%s] Not reachable, or not running on this currency. Skipping.", peer.getCurrency(), peer));
            return null;
        }

        SynchroResult result = new SynchroResult();

        // Get the last execution time (or 0 is never synchronized)
        // If not the first synchro, add a delay to last execution time
        // to avoid missing data because incorrect clock configuration
        long lastExecutionTime = getLastExecutionTime(peer);

        // Execute actions
        final long fromTime = lastExecutionTime > 0 ? lastExecutionTime - pluginSettings.getSynchroTimeOffset() : 0;
        List<SynchroAction> executedActions = actions.stream()
                .filter(a -> a.getEndPointApi().name().equals(peer.getApi()))
                .map(a -> {
                    try {
                        a.handleSynchronize(peer, fromTime, result);
                    } catch(Exception e) {
                        logger.error(String.format("[%s] [%s] Failed to execute synchro action: %s", peer.getCurrency(), peer, e.getMessage()), e);
                    }
                    return a;
                })
                .collect(Collectors.toList());

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("[%s] [%s] User data imported in %s ms: %s", peer.getCurrency(), peer, System.currentTimeMillis() - now, result.toString()));
        }

        saveExecution(peer, result);

        // Start listen changes on this peer
        if (enableSynchroWebsocket) {
            startListenChangesOnPeer(peer, executedActions);
        }

        return result;
    }

    /* -- protected methods -- */

    protected List<Peer> getConfigPingPeers(List<String> currencyIds, final EndpointApi api) {
        String[] endpoints = pluginSettings.getSynchroPingEndpoints();
        if (ArrayUtils.isEmpty(endpoints)) return null;

        List<Peer> peers = Lists.newArrayList();
        for (String endpoint: endpoints) {
            try {
                String[] endpointPart = endpoint.split(":");

                if (endpointPart.length == 2) {
                    String currency = endpointPart[0];
                    NetworkPeering.Endpoint ep = Endpoints.parse(endpointPart[1]);
                    if (ep.api == api && currencyIds.contains(currency)) {
                        Peer peer = Peer.newBuilder()
                                .setEndpoint(ep)
                                .setCurrency(currency)
                                .build();

                        String peerId = cryptoService.hash(peer.computeKey());
                        peer.setId(peerId);

                        peers.add(peer);
                    }
                }
                else {
                    logger.warn(String.format("Unable to parse P2P endpoint [%s]: %s", endpoint));
                }
            } catch (IOException e) {
                logger.warn(String.format("Unable to parse P2P endpoint [%s]: %s", endpoint, e.getMessage()));
            }
        }
        return peers;
    }

    protected List<Peer> getPeersFromApi(final EndpointApi api) {
        Preconditions.checkNotNull(api);

        try {
            List<String> currencyIds = currencyDao.getCurrencyIds();
            if (CollectionUtils.isEmpty(currencyIds)) return null;

            // Get default peer, defined in config option
            List<Peer> peers = getConfigPingPeers(currencyIds, api);
            if (peers == null) {
                peers = Lists.newArrayList();
            }

            peers.addAll(currencyIds.stream()
                    .map(currencyId -> peerDao.getPeersByCurrencyIdAndApi(currencyId, api.name()))
                    .filter(Objects::nonNull)
                    .flatMap(List::stream)
                    .collect(Collectors.toList()));

            return peers;
        }
        catch (Exception e) {
            logger.error(String.format("Could not get peers for Api [%s]", api.name()), e);
            return null;
        }
    }

    protected boolean hasSomePeers() {

        List<String> currencyIds = currencyDao.getCurrencyIds();
        if (CollectionUtils.isEmpty(currencyIds)) return false;

        for (String currencyId: currencyIds) {
            boolean hasSome = peerDao.hasPeersUpWithApi(currencyId, peerApiFilters);
            if (hasSome) return true;
        }

        return false;
    }

    protected boolean waitPeersReady() throws InterruptedException{
        final int sleepTime = 10 * 1000 /*10s*/;

        int maxWaitingDuration = 5 * 6 * sleepTime; // 5 min
        int waitingDuration = 0;
        while (!isReady() && !hasSomePeers()) {
            // Wait 10s
            Thread.sleep(sleepTime);
            waitingDuration += sleepTime;
            if (waitingDuration >= maxWaitingDuration) {
                logger.warn(String.format("Could not start data synchronisation. No Peer found (after waiting %s min).", waitingDuration/60/1000));
                return false; // stop here
            }
        }

        // Wait again, to make sure all peers have been saved by NetworkService
        Thread.sleep(sleepTime*2);

        return true;
    }


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

    protected void saveExecution(Peer peer, SynchroResult result) {
        Preconditions.checkNotNull(peer);
        Preconditions.checkNotNull(peer.getId());
        Preconditions.checkNotNull(result);

        try {
            SynchroExecution execution = new SynchroExecution();
            execution.setCurrency(peer.getCurrency());
            execution.setPeer(peer.getId());
            execution.setResult(result);

            // Last execution time (in seconds)
            long executionTime = System.currentTimeMillis()/1000;
            execution.setTime(executionTime);

            synchroExecutionDao.save(execution);
        }
        catch (Exception e) {
            logger.error(String.format("Error while saving synchro execution on peer [%s]", peer), e);
        }
    }

    protected void closeWsClientEndpoints() {
        // Closing all opened WS
        wsClientEndpoints.forEach(IOUtils::closeQuietly);
        wsClientEndpoints.clear();
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
            .forEach(a -> {
                if (a.getChangeSource() != null) {
                    actionsBySource.put(a.getChangeSource().toString(), a);
                }
            });

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
        wsClientEndpoints.add(wsClientEndPoint);
    }

    protected boolean isAliveAndValid(Peer peer) {
        Preconditions.checkNotNull(peer);
        Preconditions.checkNotNull(peer.getCurrency());

        try {
            // TODO: check version is compatible
            //String version = networkService.getVersion(peer);

            Currency currency = currencyDao.getById(peer.getCurrency());
            if (currency == null) return false;

            BlockchainBlock block = httpService.executeRequest(peer, String.format("/%s/block/0/_source", peer.getCurrency()), BlockchainBlock.class);

            return Objects.equals(block.getCurrency(), peer.getCurrency()) &&
                   Objects.equals(block.getSignature(), currency.getFirstBlockSignature());

        }
        catch(Exception e) {
            logger.debug(String.format("[%s] [%s] Peer not alive or invalid: %s", peer.getCurrency(), peer, e.getMessage()));
            return false;
        }
    }
}
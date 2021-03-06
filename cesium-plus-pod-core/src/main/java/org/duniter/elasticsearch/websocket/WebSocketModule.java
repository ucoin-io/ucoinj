package org.duniter.elasticsearch.websocket;

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


import org.duniter.elasticsearch.websocket.netty.NettyWebSocketBlockHandler;
import org.duniter.elasticsearch.websocket.netty.NettyWebSocketChangesHandler;
import org.duniter.elasticsearch.websocket.netty.NettyWebSocketPeerHandler;
import org.duniter.elasticsearch.websocket.tyrus.WebSocketBlockEndPoint;
import org.duniter.elasticsearch.websocket.tyrus.WebSocketChangesEndPoint;
import org.elasticsearch.common.inject.AbstractModule;

public class WebSocketModule extends AbstractModule {
    @Override
    protected void configure() {

        // Netty handler
        bind(NettyWebSocketBlockHandler.Init.class).asEagerSingleton();
        bind(NettyWebSocketChangesHandler.Init.class).asEagerSingleton();
        bind(NettyWebSocketPeerHandler.Init.class).asEagerSingleton();


        // Tyrus Web socket Server
        bind(WebSocketChangesEndPoint.Init.class).asEagerSingleton();
        bind(WebSocketBlockEndPoint.Init.class).asEagerSingleton();

    }

}

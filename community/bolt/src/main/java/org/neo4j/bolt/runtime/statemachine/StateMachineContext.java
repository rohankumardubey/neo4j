/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.runtime.statemachine;

import java.time.Clock;

import org.neo4j.bolt.messaging.BoltIOException;
import io.netty.channel.Channel;

import org.neo4j.bolt.BoltChannel;
import org.neo4j.bolt.runtime.BoltConnectionFatality;
import org.neo4j.bolt.runtime.BoltProtocolBreachFatality;
import org.neo4j.bolt.security.auth.AuthenticationResult;
import org.neo4j.bolt.v41.messaging.RoutingContext;

public interface StateMachineContext
{
    void authenticatedAsUser( String username, String userAgent );

    void resolveDefaultDatabase();

    void handleFailure( Throwable cause, boolean fatal ) throws BoltConnectionFatality;

    boolean resetMachine() throws BoltConnectionFatality;

    BoltStateMachineSPI boltSpi();

    MutableConnectionState connectionState();

    Clock clock();

    String connectionId();

    void initStatementProcessorProvider( AuthenticationResult authResult, RoutingContext routingContext );

    StatementProcessor setCurrentStatementProcessorForDatabase( String databaseName ) throws BoltProtocolBreachFatality, BoltIOException;

    BoltChannel channel();
}

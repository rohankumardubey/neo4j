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
package org.neo4j.bolt.v43.runtime;

import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.neo4j.bolt.messaging.RequestMessage;
import org.neo4j.bolt.messaging.ResponseMessage;
import org.neo4j.bolt.packstream.Neo4jPack;
import org.neo4j.bolt.testing.TransportTestUtil;
import org.neo4j.bolt.testing.client.SecureSocketConnection;
import org.neo4j.bolt.testing.client.SecureWebSocketConnection;
import org.neo4j.bolt.testing.client.SocketConnection;
import org.neo4j.bolt.testing.client.TransportConnection;
import org.neo4j.bolt.testing.client.WebSocketConnection;
import org.neo4j.bolt.transport.Neo4jWithSocket;
import org.neo4j.bolt.transport.Neo4jWithSocketExtension;
import org.neo4j.bolt.v3.messaging.request.CommitMessage;
import org.neo4j.bolt.v3.messaging.request.HelloMessage;
import org.neo4j.bolt.v3.messaging.request.ResetMessage;
import org.neo4j.bolt.v3.messaging.request.RollbackMessage;
import org.neo4j.bolt.v4.messaging.BeginMessage;
import org.neo4j.bolt.v4.messaging.PullMessage;
import org.neo4j.bolt.v4.messaging.RunMessage;
import org.neo4j.bolt.v4.runtime.bookmarking.BookmarkWithDatabaseId;
import org.neo4j.bolt.v43.BoltProtocolV43;
import org.neo4j.bolt.v43.BoltProtocolV43ComponentFactory;
import org.neo4j.bolt.v43.messaging.request.RouteMessage;
import org.neo4j.configuration.Config;
import org.neo4j.fabric.FabricDatabaseManager;
import org.neo4j.internal.helpers.HostnamePort;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.testdirectory.EphemeralTestDirectoryExtension;
import org.neo4j.values.AnyValue;
import org.neo4j.values.virtual.MapValue;
import org.neo4j.values.virtual.MapValueBuilder;

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.neo4j.bolt.testing.MessageConditions.msgFailure;
import static org.neo4j.bolt.testing.MessageConditions.msgIgnored;
import static org.neo4j.bolt.testing.MessageConditions.msgRecord;
import static org.neo4j.bolt.testing.MessageConditions.msgSuccess;
import static org.neo4j.bolt.testing.MessageConditions.serialize;
import static org.neo4j.bolt.testing.StreamConditions.eqRecord;
import static org.neo4j.bolt.testing.TransportTestUtil.eventuallyReceives;
import static org.neo4j.bolt.transport.Neo4jWithSocket.withOptionalBoltEncryption;
import static org.neo4j.internal.helpers.collection.MapUtil.map;
import static org.neo4j.kernel.impl.util.ValueUtils.asMapValue;
import static org.neo4j.values.storable.Values.longValue;

@EphemeralTestDirectoryExtension
@Neo4jWithSocketExtension
public class BoltV43TransportIT
{
    private static final String USER_AGENT = "TestClient/4.3";

    @Inject
    public Neo4jWithSocket server;

    private HostnamePort address;
    private TransportConnection connection;
    private TransportTestUtil util;

    private static Stream<Arguments> argumentsProvider()
    {
        return Stream.of( Arguments.of( SocketConnection.class ), Arguments.of( WebSocketConnection.class ),
                          Arguments.of( SecureSocketConnection.class ), Arguments.of( SecureWebSocketConnection.class ) );
    }

    @BeforeEach
    public void setUp( TestInfo testInfo ) throws IOException
    {
        server.setConfigure( withOptionalBoltEncryption() );
        server.init( testInfo );
        address = server.lookupDefaultConnector();
        util = new TransportTestUtil( newMessageEncoder() );
    }

    protected void init( Class<? extends TransportConnection> connectionClass ) throws Exception
    {
        connection = connectionClass.getDeclaredConstructor().newInstance();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if ( connection != null )
        {
            connection.disconnect();
        }
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "argumentsProvider" )
    public void shouldReturnTheRoutingTableInTheSuccessMessageWhenItReceivesTheRouteMessage( Class<? extends TransportConnection> connectionClass )
            throws Exception
    {
        init( connectionClass );

        negotiateBoltV43();

        connection.send( util.chunk( new RouteMessage( new MapValueBuilder().build(),
                                                       List.of(), null ) ) );

        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( metadata ->
                            {
                                assertThat( metadata.containsKey( "rt" ) ).isTrue();
                                assertThat( metadata.get( "rt" ) )
                                        .isInstanceOf( Map.class )
                                        .satisfies( rt -> assertRoutingTableHasCorrectShape( (Map<?,?>) rt ) );
                            } )
        ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "argumentsProvider" )
    public void shouldReturnTheRoutingTableInTheSuccessMessageWhenItReceivesTheRouteMessageWithBookmark( Class<? extends TransportConnection> connectionClass )
            throws Exception
    {
        init( connectionClass );

        negotiateBoltV43();

        var lastClosedTransactionId = getLastClosedTransactionId();
        var routeBookmark = new BookmarkWithDatabaseId( lastClosedTransactionId, getDatabaseId() );

        connection.send( util.chunk( new RouteMessage( new MapValueBuilder().build(),
                                                       List.of( routeBookmark ), null ) ) );

        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( metadata ->
                            {
                                assertThat( metadata.containsKey( "rt" ) ).isTrue();
                                assertThat( metadata.get( "rt" ) )
                                        .isInstanceOf( Map.class )
                                        .satisfies( rt -> assertRoutingTableHasCorrectShape( (Map<?,?>) rt ) );
                            } )
        ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "argumentsProvider" )
    public void shouldReturnFailureIfRoutingTableFailedToReturn( Class<? extends TransportConnection> connectionClass )
            throws Exception
    {
        init( connectionClass );

        negotiateBoltV43();

        connection.send( util.chunk( new RouteMessage(
                new MapValueBuilder().build(), List.of(), "DOESNT_EXIST!" ) ) );

        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgFailure()
        ) );

        connection.send( util.chunk( ResetMessage.INSTANCE ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );

        connection.send( util.chunk( new RouteMessage( new MapValueBuilder().build(), List.of(), null ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( metadata ->
                            {
                                assertThat( metadata.containsKey( "rt" ) ).isTrue();
                                assertThat( metadata.get( "rt" ) )
                                        .isInstanceOf( Map.class )
                                        .satisfies( rt -> assertRoutingTableHasCorrectShape( (Map<?,?>) rt ) );
                            } ) ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "argumentsProvider" )
    public void shouldIgnoreRouteMessageWhenInFailedState( Class<? extends TransportConnection> connectionClass ) throws Exception
    {
        init( connectionClass );

        negotiateBoltV43();

        connection.send( util.chunk( new RouteMessage(
                MapValue.EMPTY, List.of(), "DOESNT_EXIST!" ) ) );

        assertThat( connection ).satisfies( util.eventuallyReceives( msgFailure() ) );

        connection.send( util.chunk( new RouteMessage( MapValue.EMPTY, List.of(), null ) ) );

        assertThat( connection ).satisfies( util.eventuallyReceives( msgIgnored() ) );
    }

    private void assertRoutingTableHasCorrectShape( Map<?,?> routingTable )
    {
        assertAll(
                () ->
                {
                    assertThat( routingTable.containsKey( "ttl" ) ).isTrue();
                    assertThat( routingTable.get( "ttl" ) ).isInstanceOf( Long.class );
                },
                () ->
                {
                    assertThat( routingTable.containsKey( "servers" ) ).isTrue();
                    assertThat( routingTable.get( "servers" ) )
                            .isInstanceOf( List.class )
                            .satisfies( s ->
                                        {
                                            var servers = (List<?>) s;
                                            for ( var srv : servers )
                                            {
                                                assertThat( srv ).isInstanceOf( Map.class );
                                                var server = (Map<?,?>) srv;
                                                assertAll(
                                                        () ->
                                                        {
                                                            assertThat( server.containsKey( "role" ) ).isTrue();
                                                            assertThat( server.get( "role" ) )
                                                                    .isIn( "READ", "WRITE", "ROUTE" );
                                                        },
                                                        () ->
                                                        {
                                                            assertThat( server.containsKey( "addresses" ) )
                                                                    .isTrue();
                                                            assertThat( server.get( "addresses" ) )
                                                                    .isInstanceOf( List.class )
                                                                    .satisfies( ad ->
                                                                                {
                                                                                    var addresses =
                                                                                            (List<?>) ad;
                                                                                    for ( var address : addresses )
                                                                                    {
                                                                                        assertThat( address )
                                                                                                .isInstanceOf(
                                                                                                        String.class );
                                                                                    }
                                                                                } );
                                                        }
                                                );
                                            }
                                        } );
                }
        );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "argumentsProvider" )
    public void shouldReturnUpdatedBookmarkAfterAutoCommitTransaction( Class<? extends TransportConnection> connectionClass ) throws Exception
    {
        init( connectionClass );

        assumeFalse( FabricDatabaseManager.fabricByDefault( Config.defaults() ) );

        negotiateBoltV43();

        // bookmark is expected to advance once the auto-commit transaction is committed
        var lastClosedTransactionId = getLastClosedTransactionId();
        var expectedBookmark = new BookmarkWithDatabaseId( lastClosedTransactionId + 1, getDatabaseId() ).toString();

        connection.send( util.chunk( new RunMessage( "CREATE ()" ), new PullMessage( asMapValue( map( "n", -1L ) ) ) ) );

        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess(),
                msgSuccess( responseMessage -> assertThat( responseMessage )
                        .containsEntry( "bookmark", expectedBookmark ) ) ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "argumentsProvider" )
    public void shouldReturnUpdatedBookmarkAfterExplicitTransaction( Class<? extends TransportConnection> connectionClass ) throws Exception
    {
        init( connectionClass );

        assumeFalse( FabricDatabaseManager.fabricByDefault( Config.defaults() ) );

        negotiateBoltV43();

        // bookmark is expected to advance once the auto-commit transaction is committed
        var lastClosedTransactionId = getLastClosedTransactionId();
        var expectedBookmark = new BookmarkWithDatabaseId( lastClosedTransactionId + 1, getDatabaseId() ).toString();

        connection.send( util.chunk( new BeginMessage() ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );

        connection.send( util.chunk( new RunMessage( "CREATE ()" ), new PullMessage( asMapValue( map( "n", -1L ) ) ) ) );

        assertThat( connection )
                .satisfies( util.eventuallyReceives( msgSuccess(),
                                                     msgSuccess( message -> assertThat( message ).doesNotContainEntry( "bookmark", expectedBookmark ) ) ) );

        connection.send( util.chunk( CommitMessage.COMMIT_MESSAGE ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( message -> assertThat( message ).containsEntry( "bookmark", expectedBookmark ) ) ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "argumentsProvider" )
    public void shouldStreamWhenStatementIdNotProvided( Class<? extends TransportConnection> connectionClass ) throws Exception
    {
        init( connectionClass );

        negotiateBoltV43();

        // begin a transaction
        connection.send( util.chunk( new BeginMessage() ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );

        // execute a query
        connection.send( util.chunk( new RunMessage( "UNWIND range(30, 40) AS x RETURN x" ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( message -> assertThat( message )
                        .containsEntry( "qid", 0L )
                        .containsKeys( "fields", "t_first" ) ) ) );

        // request 5 records but do not provide qid
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 5L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 30L ) ) ),
                msgRecord( eqRecord( longValueCondition( 31L ) ) ),
                msgRecord( eqRecord( longValueCondition( 32L ) ) ),
                msgRecord( eqRecord( longValueCondition( 33L ) ) ),
                msgRecord( eqRecord( longValueCondition( 34L ) ) ),
                msgSuccess( singletonMap( "has_more", true ) ) ) );

        // request 2 more records but do not provide qid
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 2L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 35L ) ) ),
                msgRecord( eqRecord( longValueCondition( 36L ) ) ),
                msgSuccess( singletonMap( "has_more", true ) ) ) );

        // request 3 more records and provide qid
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 3L, "qid", 0L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 37L ) ) ),
                msgRecord( eqRecord( longValueCondition( 38L ) ) ),
                msgRecord( eqRecord( longValueCondition( 39L ) ) ),
                msgSuccess( singletonMap( "has_more", true ) ) ) );

        // request 10 more records but do not provide qid, only 1 more record is available
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 10L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 40L ) ) ),
                msgSuccess( message -> assertThat( message ).containsKey( "t_last" )
                                                            .doesNotContainKey( "has_more" ) ) ) );

        // rollback the transaction
        connection.send( util.chunk( RollbackMessage.ROLLBACK_MESSAGE ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "argumentsProvider" )
    public void shouldSendAndReceiveStatementIds( Class<? extends TransportConnection> connectionClass ) throws Exception
    {
        init( connectionClass );

        negotiateBoltV43();

        // begin a transaction
        connection.send( util.chunk( new BeginMessage() ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );

        // execute query #0
        connection.send( util.chunk( new RunMessage( "UNWIND range(1, 10) AS x RETURN x" ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( message -> assertThat( message ).containsEntry( "qid", 0L ).containsKeys( "fields", "t_first" ) ) ) );

        // request 3 records for query #0
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 3L, "qid", 0L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 1L ) ) ),
                msgRecord( eqRecord( longValueCondition( 2L ) ) ),
                msgRecord( eqRecord( longValueCondition( 3L ) ) ),
                msgSuccess( singletonMap( "has_more", true ) ) ) );

        // execute query #1
        connection.send( util.chunk( new RunMessage( "UNWIND range(11, 20) AS x RETURN x" ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( message -> assertThat( message ).containsEntry( "qid", 1L ).containsKeys( "fields", "t_first" ) ) ) );

        // request 2 records for query #1
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 2L, "qid", 1L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 11L ) ) ),
                msgRecord( eqRecord( longValueCondition( 12L ) ) ),
                msgSuccess( singletonMap( "has_more", true ) ) ) );

        // execute query #2
        connection.send( util.chunk( new RunMessage( "UNWIND range(21, 30) AS x RETURN x" ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( message -> assertThat( message ).containsEntry( "qid", 2L ).containsKeys( "fields", "t_first" ) ) ) );

        // request 4 records for query #2
        // no qid - should use the statement from the latest RUN
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 4L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 21L ) ) ),
                msgRecord( eqRecord( longValueCondition( 22L ) ) ),
                msgRecord( eqRecord( longValueCondition( 23L ) ) ),
                msgRecord( eqRecord( longValueCondition( 24L ) ) ),
                msgSuccess( singletonMap( "has_more", true ) ) ) );

        // execute query #3
        connection.send( util.chunk( new RunMessage( "UNWIND range(31, 40) AS x RETURN x" ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgSuccess( message -> assertThat( message ).containsEntry( "qid", 3L ).containsKeys( "fields", "t_first" ) ) ) );

        // request 1 record for query #3
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 1L, "qid", 3L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 31L ) ) ),
                msgSuccess( singletonMap( "has_more", true ) ) ) );

        // request 2 records for query #0
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 2L, "qid", 0L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 4L ) ) ),
                msgRecord( eqRecord( longValueCondition( 5L ) ) ),
                msgSuccess( singletonMap( "has_more", true ) ) ) );

        // request 9 records for query #3
        connection.send( util.chunk( new PullMessage( asMapValue( map( "n", 9L, "qid", 3L ) ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives(
                msgRecord( eqRecord( longValueCondition( 32L ) ) ),
                msgRecord( eqRecord( longValueCondition( 33L ) ) ),
                msgRecord( eqRecord( longValueCondition( 34L ) ) ),
                msgRecord( eqRecord( longValueCondition( 35L ) ) ),
                msgRecord( eqRecord( longValueCondition( 36L ) ) ),
                msgRecord( eqRecord( longValueCondition( 37L ) ) ),
                msgRecord( eqRecord( longValueCondition( 38L ) ) ),
                msgRecord( eqRecord( longValueCondition( 39L ) ) ),
                msgRecord( eqRecord( longValueCondition( 40L ) ) ),
                msgSuccess( message -> assertThat( message ).containsKey( "t_last" ).doesNotContainKey( "has_more" ) ) ) );

        // commit the transaction
        connection.send( util.chunk( CommitMessage.COMMIT_MESSAGE ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );
    }

    private void negotiateBoltV43() throws Exception
    {
        connection.connect( address ).send( util.acceptedVersions( BoltProtocolV43.VERSION.toInt(), 0, 0, 0 ) );
        assertThat( connection ).satisfies( eventuallyReceives( new byte[]{0, 0, 3, 4} ) );

        connection.send( util.chunk( new HelloMessage( map( "user_agent", USER_AGENT ) ) ) );
        assertThat( connection ).satisfies( util.eventuallyReceives( msgSuccess() ) );
    }

    private long getLastClosedTransactionId()
    {
        var resolver = ((GraphDatabaseAPI) server.graphDatabaseService()).getDependencyResolver();
        var txIdStore = resolver.resolveDependency( TransactionIdStore.class );
        return txIdStore.getLastClosedTransactionId();
    }

    private NamedDatabaseId getDatabaseId()
    {
        var resolver = ((GraphDatabaseAPI) server.graphDatabaseService()).getDependencyResolver();
        var database = resolver.resolveDependency( Database.class );
        return database.getNamedDatabaseId();
    }

    private static Condition<AnyValue> longValueCondition( long expected )
    {
        return new Condition<>( value -> value.equals( longValue( expected ) ), "equals" );
    }

    public static TransportTestUtil.MessageEncoder newMessageEncoder()
    {
        return new TransportTestUtil.MessageEncoder()
        {
            @Override
            public byte[] encode( Neo4jPack neo4jPack, RequestMessage... messages ) throws IOException
            {
                return BoltProtocolV43ComponentFactory.encode( neo4jPack, messages );
            }

            @Override
            public byte[] encode( Neo4jPack neo4jPack, ResponseMessage... messages ) throws IOException
            {
                return serialize( neo4jPack, messages );
            }
        };
    }
}

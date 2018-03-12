/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package io.ballerina.messaging.broker.core.transaction;

import io.ballerina.messaging.broker.common.ValidationException;
import io.ballerina.messaging.broker.core.Broker;
import io.ballerina.messaging.broker.core.BrokerException;
import io.ballerina.messaging.broker.core.Message;
import io.ballerina.messaging.broker.core.QueueHandler;
import io.ballerina.messaging.broker.core.store.MessageStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.transaction.xa.Xid;

/**
 * XA transaction information hold within the broker.
 */
public class Branch implements EnqueueDequeueStrategy {

    /**
     * States of a {@link Branch}
     */
    public enum State {

        /**
         * Branch is registered in {@link Registry}
         */
        ACTIVE,

        /**
         * Branch can only be rolled back
         */
        ROLLBACK_ONLY,

        /**
         * Branch received a prepare call and in the process of persisting
         */
        PRE_PREPARE,

        /**
         * Branch was unregistered from {@link Registry}
         */
        FORGOTTEN,

        /**
         * Branch is in prepared state. Branch can only be committed or rolled back after this
         */
        PREPARED,

        /**
         * Branch heuristically committed
         */
        HEUR_COM,

        /**
         * Branch heuristically rolled back
         */
        HEUR_RB
    }

    /**
     * States of associated sessions for the branch.
     */
    private enum SessionState {

        /**
         * Branch is registered in {@link Registry}
         */
        ACTIVE,
        /**
         * The branch was suspended in a dtx.end
         */
        SUSPENDED,
    }

    private State state;

    private Xid xid;

    private final MessageStore messageStore;

    private final Set<QueueHandler> affectedQueueHandlers;

    private final Broker broker;

    private final Map<Integer, SessionState> associatedSessions;

    Branch(Xid xid, MessageStore messageStore, Broker broker) {
        this.xid = xid;
        this.messageStore = messageStore;
        this.broker = broker;
        messageStore.branch(xid);
        this.affectedQueueHandlers = new HashSet<>();
        this.associatedSessions = new HashMap<>();
        state = State.ACTIVE;
    }

    @Override
    public void enqueue(Message message) throws BrokerException {
        Set<QueueHandler> queueHandlers = broker.enqueue(xid, message);
        affectedQueueHandlers.addAll(queueHandlers);
    }

    @Override
    public void dequeue(String queueName, Message message) throws BrokerException {
        QueueHandler queueHandler = broker.dequeue(xid, queueName, message);
        affectedQueueHandlers.add(queueHandler);
    }

    public void prepare() throws BrokerException {
        messageStore.prepare(xid);
    }

    public void commit(boolean onePhase) throws BrokerException {
        messageStore.flush(xid, onePhase);
        for (QueueHandler queueHandler: affectedQueueHandlers) {
            queueHandler.commit(xid);
        }
    }

    public void rollback() {
        messageStore.clear(xid);
        rollbackQueueHandlers();
    }

    public void dtxRollback() throws BrokerException {
        messageStore.cancel(xid);
        rollbackQueueHandlers();
    }

    private void rollbackQueueHandlers() {
        for (QueueHandler queueHandler: affectedQueueHandlers) {
            queueHandler.rollback(xid);
        }
    }

    public Xid getXid() {
        return xid;
    }

    public void setState(State state) {
        this.state = state;
    }

    public State getState() {
        return state;
    }

    /**
     * Associate a session to current branch.
     *
     * @param sessionId session identifier of the session
     */
    public void associateSession(int sessionId) {
        associatedSessions.put(sessionId, SessionState.ACTIVE);
    }

    /**
     * Resume a session if it is suspended
     *
     * @param sessionId session identifier of the session
     */
    public void resumeSession(int sessionId) throws ValidationException {
        if (associatedSessions.containsKey(sessionId) && associatedSessions.get(sessionId) == SessionState.SUSPENDED) {
            associatedSessions.put(sessionId, SessionState.ACTIVE);
        } else {
            throw new ValidationException("Couldn't resume session for branch with xid " + xid
                                                  + " and session id " + sessionId);
        }
    }

    public void disassociateSession(int sessionId) {
        associatedSessions.remove(sessionId);
    }

    public void suspendSession(int sessionId) {
        SessionState associatedState = associatedSessions.get(sessionId);
        if (Objects.nonNull(associatedState) && associatedState == SessionState.ACTIVE) {
            associatedSessions.put(sessionId, SessionState.SUSPENDED);
        }
    }

    /**
     * Check if a session is associated with the branch
     *
     * @param sessionId session identifier of the session
     * @return True is the session is associated with the branch
     */
    public boolean isAssociated(int sessionId) {
        return associatedSessions.containsKey(sessionId);
    }

    public boolean hasAssociatedActiveSessions() {
        if (hasAssociatedSessions()) {
            for (SessionState sessionState : associatedSessions.values()) {
                if (sessionState != SessionState.SUSPENDED) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if there are any associated sessions
     *
     * @return True if there are any associated sessions, false otherwise
     */
    private boolean hasAssociatedSessions() {
        return !associatedSessions.isEmpty();
    }

    public void clearAssociations() {
        associatedSessions.clear();
    }
}

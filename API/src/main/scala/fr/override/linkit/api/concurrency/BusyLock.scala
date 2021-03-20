/*
 * Copyright (c) 2021. Linkit and or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can only use it for personal uses, studies or documentation.
 * You can download this source code, and modify it ONLY FOR PERSONAL USE and you
 * ARE NOT ALLOWED to distribute your MODIFIED VERSION.
 *
 * Please contact maximebatista18@gmail.com if you need additional information or have any
 * questions.
 */

package fr.`override`.linkit.api.concurrency

import fr.`override`.linkit.api.concurrency.RelayThreadPool
import org.jetbrains.annotations.NotNull

import scala.collection.mutable

/**
 * Helper for busy locks operations, this class allows you to
 * keep your current relay worker thread busy and stop it easily.
 * */
@deprecated("Still in development...")
class BusyLock(releaseCondition: => Boolean = true) {
    /**
     * A Map containing the busy lock tickets wich are associated to a thread
     * */
    private val locks = mutable.HashMap[Thread, ThreadLocksRepertory]()

    /**
     * Keeps the current thread busy with task execution
     *
     * @see [[RelayThreadPool]]
     * */
    @relayWorkerExecution
    def keepBusyUntilRelease(@NotNull lock: AnyRef = new Object): Unit = {
        RelayThreadPool.checkCurrentIsWorker("Could not perform a busy lock in a non relay thread.")
        val pool = RelayThreadPool.currentPool().get

        val repertory = locks.getOrElseUpdate(currentThread, ThreadLocksRepertory())

        locks.put(currentThread, repertory)
        repertory.markBusy(lock)

        pool.executeRemainingTasks(lock, repertory.containsLock && releaseCondition)
        locks.remove(currentThread)
    }

    /**
     * @return true if the current lock handles busy locks with the current thread.
     * */
    def isCurrentThreadBusy: Boolean = {
        locks.get(currentThread).exists(_.containsLock)
    }

    /**
     * Releases last busy lock of the current thread
     * */
    def release(): Unit = {
        locks.get(currentThread).tapEach(_.stopWorking())
    }

    /**
     * Releases all busy locks of the current thread
     * */
    def releaseAll(): Unit = {
        locks.get(currentThread).tapEach(_.stopAllWork())
        locks.remove(currentThread)
    }

    /**
     * Releases all busy locks of all threads then clear the lock list
     * */
    def clearLocks(): Unit = {
        locks.foreachEntry((_, t) => t.stopAllWork())
        locks.clear()
    }

    @relayWorkerExecution
    private def currentPool: RelayThreadPool = {
        RelayThreadPool.checkCurrentIsWorker("Could not perform this action in a non relay thread.")
        RelayThreadPool.currentPool().get
    }

    /**
     * Busy lock repertory of one thread
     * */
    private final case class ThreadLocksRepertory() {
        private val depths = mutable.HashMap.empty[Int, AnyRef]

        /**
         * Releases the lock of the current task execution depth.
         * */
        def stopWorking(): Unit = {
            val depth = currentPool.currentTaskExecutionDepth
            val lock = depths(depth)
            lock.synchronized {
                lock.notifyAll()
            }
            depths -= depth
        }

        /**
         * Releases all the locks of this repertory
         * */
        def stopAllWork(): Unit = depths.clone().foreach {
            _ => stopWorking()
        }

        /**
         * Set the lock into the current task execution depth
         *
         * @param lock the reference to handle the monitor
         * */
        def markBusy(lock: AnyRef): Unit = {
            depths.put(currentPool.currentTaskExecutionDepth, lock)
        }

        /**
         * @return true if the current task execution depth is associated with this locks repertory
         *         in other worlds, if this repertory handles a busy lock associated with current
         *         execution depth, this means that we must
         * */
        def containsLock: Boolean = {
            depths.contains(currentPool.currentTaskExecutionDepth)
        }
    }

}

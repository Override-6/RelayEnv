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

package fr.linkit.core.connection.network.cache.collection

import fr.linkit.api.connection.network.cache.{SharedCacheFactory, SharedCacheManager}
import fr.linkit.api.connection.packet.Bundle
import fr.linkit.api.connection.packet.traffic.{PacketSender, PacketSyncReceiver}
import fr.linkit.api.local.system.AppLogger
import fr.linkit.core.connection.network.cache.AbstractSharedCache
import fr.linkit.core.connection.network.cache.collection.CollectionModification._
import fr.linkit.core.connection.network.cache.collection.SharedCollection.CollectionAdapter
import fr.linkit.core.connection.packet.fundamental.RefPacket.ObjectPacket
import fr.linkit.core.local.concurrency.pool.BusyWorkerPool
import fr.linkit.core.local.utils.ConsumerContainer
import org.jetbrains.annotations.{NotNull, Nullable}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.control.NonFatal

class SharedCollection[A <: Serializable : ClassTag](handler: SharedCacheManager,
                                                     identifier: Long,
                                                     adapter: CollectionAdapter[A],
                                                     channel: PacketSender with PacketSyncReceiver)
        extends AbstractSharedCache[A](handler, identifier, channel) with mutable.Iterable[A] {

    private val collectionModifications = ListBuffer.empty[(CollectionModification, Long, Any)]
    private val networkListeners        = ConsumerContainer[(CollectionModification, Long, A)]()

    @volatile private  var modCount           = 0
    @volatile override var autoFlush: Boolean = true

    override def modificationCount(): Int = modCount

    override def flush(): this.type = this.synchronized {
        collectionModifications.foreach(flushModification)
        collectionModifications.clear()
        this
    }

    override def toString: String = getClass.getSimpleName + s"(family: $family, id: $identifier, content: ${adapter.toString})"

    override def currentContent: Array[Any] = adapter.get().toArray

    override def iterator: Iterator[A] = adapter.iterator

    override final def handleBundle(bundle: Bundle): Unit = {
        bundle.packet match {
            case modPacket: ObjectPacket => BusyWorkerPool.runLaterOrHere {
                handleNetworkModRequest(modPacket)
            }
        }
    }

    override protected def setCurrentContent(content: Array[A]): Unit = set(content)

    def contains(a: Any): Boolean = adapter.contains(a)

    def add(t: A): this.type = {
        adapter.add(t)
        addLocalModification(ADD, -1, t)
        this
    }

    def foreach(action: A => Unit): this.type = {
        adapter.get().foreach(action)
        this
    }

    def add(i: Int, t: A): this.type = {
        adapter.insert(i, t)
        addLocalModification(ADD, i, t)
        this
    }

    def set(i: Int, t: A): this.type = {
        addLocalModification(SET, i, adapter.set(i, t))
        this
    }

    def set(content: Array[A]): this.type = {
        clear()
        var i = -1
        content.foreach({
            i += 1
            add(i, _)
        })
        this
    }

    def remove(i: Int): this.type = {
        addLocalModification(REMOVE, i, adapter.remove(i))
        this
    }

    def remove(any: Any): this.type = {
        remove(adapter.get().indexOf(any))
    }

    def clear(): this.type = {
        adapter.clear()
        addLocalModification(CLEAR, -1, null)
        this
    }

    def mapped[B](map: A => B): BoundedCollection.Immutable[B] = {
        adapter.createBoundedCollection(map)
    }

    /**
     * (CollectionModification, _, _) : the kind of modification that were done
     * (_, Int, _) : the index affected (may be -1 for mod kinds that does not specify any index such as CLEAR)
     * (_, _, T) : The object affected (may be null for mod kinds that does not specify any object such as CLEAR, or REMOVE)
     * */
    def addListener(action: (CollectionModification, Long, A) => Unit): this.type = {
        networkListeners += (tuple3 => action(tuple3._1, tuple3._2, tuple3._3))
        this
    }

    private def addLocalModification(@NotNull kind: CollectionModification, @Nullable index: Int, @Nullable value: Any): Unit = {
        AppLogger.vDebug(s"<$family> Local modification : ${(kind, index, value)}")
        if (autoFlush) {
            flushModification((kind, index, value))
            return
        }

        kind match {
            case CLEAR        => collectionModifications.clear()
            case SET | REMOVE => collectionModifications.filterInPlace(m => !((m._1 == SET || m._1 == REMOVE) && m._2 == index))
            case ADD          => //Do not optimise : the addition result may be different according to the order
        }
        collectionModifications += ((kind, index, value))
    }

    private def flushModification(mod: (CollectionModification, Long, Any)): Unit = {
        sendRequest(ObjectPacket(mod))
        networkListeners.applyAllLater(mod.asInstanceOf[(CollectionModification, Long, A)])
        modCount += 1
        AppLogger.vWarn(s"<$family> (${channel.traffic.supportIdentifier}) COLLECTION IS NOW (local): " + this)
    }

    private def handleNetworkModRequest(packet: ObjectPacket): Unit = {
        val mod    : (CollectionModification, Long, Any) = packet.casted
        val modKind: CollectionModification              = mod._1
        val index                                        = mod._2.toInt
        lazy val item: A = mod._3.asInstanceOf[A] //Only instantiate value if needed (could occur to NPE)

        AppLogger.vWarn(s"<$family> Received mod request : $mod")
        AppLogger.vWarn(s"<$family> Current items : $this")
        val action: CollectionAdapter[A] => Unit = modKind match {
            case CLEAR  => _.clear()
            case SET    => _.set(index, item)
            case REMOVE => _.remove(index)
            case ADD    => if (index < 0) _.add(item) else _.insert(index, item)
        }

        try {
            action(adapter)
        } catch {
            case NonFatal(e) =>
                AppLogger.printStackTrace(e)
                System.exit(1)
        }
        modCount += 1

        networkListeners.applyAllLater(mod.asInstanceOf[(CollectionModification, Long, A)])
        AppLogger.vWarn(s"<$family> COLLECTION IS NOW (network) $this")
    }
}

object SharedCollection {

    private type S[A] = mutable.Seq[A] with mutable.Growable[A] with mutable.Buffer[A]

    def set[A <: Serializable : ClassTag]: SharedCacheFactory[SharedCollection[A]] = {
        ofInsertFilter[A]((coll, it) => {
            val b = !coll.contains(it)
            b
        })
    }

    def buffer[A <: Serializable : ClassTag]: SharedCacheFactory[SharedCollection[A]] = {
        ofInsertFilter[A]((_, _) => true)
    }

    def apply[A <: Serializable : ClassTag]: SharedCacheFactory[SharedCollection[A]] = buffer[A]

    /**
     * The insertFilter must be true in order to authorise the insertion
     * */
    def ofInsertFilter[A <: Serializable : ClassTag](insertFilter: (CollectionAdapter[A], A) => Boolean): SharedCacheFactory[SharedCollection[A]] = {
        (handler: SharedCacheManager, identifier: Long, baseContent: Array[Any], channel: PacketSender with PacketSyncReceiver) => {
            var adapter: CollectionAdapter[A] = null
            adapter = new CollectionAdapter[A](baseContent.asInstanceOf[Array[A]], insertFilter(adapter, _))

            new SharedCollection[A](handler, identifier, adapter, channel)
        }
    }

    /**
     * The insertFilter must be true in order to authorise the insertion
     * */
    class CollectionAdapter[A](baseContent: Array[A], insertFilter: A => Boolean) extends mutable.Iterable[A] {

        type X
        private val mainCollection                                          = ListBuffer.from(baseContent)
        private val boundedCollections: ListBuffer[BoundedCollection[A, X]] = ListBuffer.empty

        override def toString: String = mainCollection.toString()

        override def iterator: Iterator[A] = mainCollection.iterator

        def createBoundedCollection[B](map: A => B): BoundedCollection.Immutable[B] = {
            val boundedCollection = new BoundedCollection[A, B](map)
            boundedCollections += boundedCollection.asInstanceOf[BoundedCollection[A, X]]
            val mainContent = mainCollection.synchronized {
                mainCollection.toArray[Any].asInstanceOf[Array[A]]
            }
            boundedCollection.set(mainContent)
            boundedCollection
        }

        def clear(): Unit = {
            mainCollection.synchronized {
                mainCollection.clear()
            }
            foreachCollection(_.clear())
        }

        def set(i: Int, it: A): Unit = {
            mainCollection.synchronized {
                mainCollection.update(i, it)
            }
            foreachCollection(_.add(i, it))
        }

        def insert(i: Int, it: A): Unit = {
            if (!insertFilter(it))
                return
            mainCollection.synchronized {
                mainCollection.insert(i, it)
            }
            foreachCollection(_.add(i, it))
        }

        def add(it: A): Int = {
            if (!insertFilter(it))
                return -1
            mainCollection.synchronized {
                mainCollection += it
            }
            foreachCollection(_.add(it))
            mainCollection.size - 1
        }

        def set(array: Array[A]): Unit = {
            mainCollection.synchronized {
                mainCollection.clear()
                mainCollection ++= array
            }
            foreachCollection(_.set(array))
        }

        def remove(i: Int): A = {
            val removed = mainCollection.synchronized {
                mainCollection.remove(i)
            }
            foreachCollection(_.remove(i))
            removed
        }

        def contains(a: Any): Boolean = mainCollection.synchronized {
            mainCollection.contains(a)
        }

        private[SharedCollection] def get(): S[A] = mainCollection

        private def foreachCollection(action: BoundedCollection.Mutator[A] => Unit): Unit =
            BusyWorkerPool.runLaterOrHere {
                mainCollection.synchronized {
                    Array.from(boundedCollections)
                }.foreach(action)
            }
    }

}
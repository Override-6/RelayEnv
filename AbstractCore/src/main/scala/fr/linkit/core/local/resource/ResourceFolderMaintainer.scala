/*
 *  Copyright (c) 2021. Linkit and or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can only use it for personal uses, studies or documentation.
 *  You can download this source code, and modify it ONLY FOR PERSONAL USE and you
 *  ARE NOT ALLOWED to distribute your MODIFIED VERSION.
 *
 *  Please contact maximebatista18@gmail.com if you need additional information or have any
 *  questions.
 */

package fr.linkit.core.local.resource

import fr.linkit.api.local.resource._
import fr.linkit.api.local.resource.exception.NoSuchResourceException
import fr.linkit.api.local.resource.external.{ExternalResource, ResourceFolder}
import fr.linkit.api.local.system.fsa.FileSystemAdapter
import fr.linkit.core.local.resource.ResourceFolderMaintainer.{MaintainerFileName, Resources, loadResources}
import fr.linkit.core.local.system.AbstractCoreConstants.{UserGson => Gson}
import fr.linkit.core.local.system.{DynamicVersions, StaticVersions}

import java.util

class ResourceFolderMaintainer(maintained: ResourceFolder,
                               listener: ResourceListener,
                               fsa: FileSystemAdapter) extends ResourcesMaintainer {

    private   val maintainerFileAdapter = fsa.getAdapter(maintained.getAdapter.getAbsolutePath + "/" + MaintainerFileName)
    protected val resources: Resources  = loadResources(fsa, maintained)
    updateFile()
    listener.putMaintainer(this, MaintainerKey)

    override def getResources: ResourceFolder = maintained

    override def isRemoteResource(name: String): Boolean = isKnown(name) && !maintained.isPresentOnDrive(name)

    override def isKnown(name: String): Boolean = name == MaintainerFileName || resources.get(name).isDefined

    override def getLastChecksum(name: String): Long = {
        resources
                .get(name)
                .map(_.lastChecksum)
                .getOrElse {
                    if (name == maintained.name) {
                        resources.folder.lastChecksum
                    } else {
                        throw NoSuchResourceException(s"Resource $name is unknown for folder resource ${maintained.getLocation}")
                    }
                }
    }

    override def getLastModified(name: String): DynamicVersions = {
        resources
                .get(name)
                .map(_.lastModified)
                .getOrElse {
                    if (maintained.isPresentOnDrive(name)) {
                        DynamicVersions.unknown
                    } else if (name == maintained.name) {
                        resources.folder.lastModified
                    } else {
                        throw NoSuchResourceException(s"Resource $name is unknown for folder resource ${maintained.getLocation}")
                    }
                }
    }

    private[resource] def unregisterResource(name: String): Unit = {
        resources -= name
    }

    private[resource] def registerResource(resource: ExternalResource): Unit = {
        if (resource.getParent.ne(maintained))
            throw new IllegalArgumentException("Given resource's parent folder is not handled by this maintainer.")

        val item = ResourceItem(resource.name)
        item.lastChecksum = resource.getChecksum
        item.lastModified = DynamicVersions.from(StaticVersions.currentVersion)

        resources put item
        updateFile()
    }

    private def updateFile(): Unit = {
        println(s"Saving resources = ${resources}")
        val json = Gson.toJson(resources)
        val out  = maintainerFileAdapter.newOutputStream()
        out.write(json.getBytes())
        out.close()
    }

    object MaintainerKey extends ResourceKey {

        override def onModify(name: String): Unit = handle(name) { (resource, item) =>
            if (!item.lastModified.sameVersions(StaticVersions.currentVersion)) {
                item.lastModified.setAll(StaticVersions.currentVersion)
            }
            item.lastChecksum = resource.getChecksum
            updateFile()
            println(s"item = ${item}")
        }

        override def onDelete(name: String): Unit = handle(name) { (_, _) =>
            maintained.unregister(name)
            updateFile()
            println(s"Unregistered $name")
        }

        override def onCreate(name: String): Unit = handle(name) { (_, _) =>
            maintained.register(name)
            updateFile()
            println(s"Registered $name")
        }

        private def handle(name: String)(callback: (ExternalResource, ResourceItem) => Unit): Unit = {
            val resource = maintained.find[ExternalResource](name)
            val item     = resources.get(name)

            if (resource.isEmpty && item.isEmpty)
                return
            if (resource.isDefined && item.isEmpty) {
                if (maintained.isPresentOnDrive(name)) {
                    registerResource(resource.get)
                } else {
                    maintained.unregister(name)
                }
            }
            if (item.isDefined && resource.isEmpty) {
                if (maintained.isPresentOnDrive(name)) {
                    maintained.register(name)
                } else {
                    unregisterResource(name)
                }
            }

            callback(resource.get, item.get)
        }
    }

}

object ResourceFolderMaintainer {

    val MaintainerFileName: String = "maintainer.json"

    private def loadResources(fsa: FileSystemAdapter, maintained: ResourceFolder): Resources = {
        val maintainerPath        = maintained.getAdapter.getAbsolutePath + "/" + MaintainerFileName
        val maintainerFileAdapter = fsa.getAdapter(maintainerPath)
        if (maintainerFileAdapter.notExists) {
            maintainerFileAdapter.createAsFile()
            return Resources(ResourceItem.minimal(maintained))
        }
        val json      = maintainerFileAdapter.getContentString
        val resources = Gson.fromJson(json, classOf[Resources])
        if (resources == null)
            return Resources(ResourceItem.minimal(maintained))

        def handleItem(item: ResourceItem): Unit = {
            val name    = item.name
            val adapter = fsa.getAdapter(maintainerFileAdapter.getAbsolutePath + "/" + name)
            if (adapter.notExists) {
                resources -= item
                return
            }

            maintained.register(name)
        }

        resources.foreach(handleItem)

        resources
    }

    case class Resources(folder: ResourceItem) {

        private val children: util.HashMap[String, ResourceItem] = new util.HashMap()

        def get(name: String): Option[ResourceItem] = Option(children.get(name))

        def foreach(action: ResourceItem => Unit): Unit = {
            children.values.toArray(Array[ResourceItem]()).foreach(action)
        }

        def put(item: ResourceItem): Unit = children.put(item.name, item)

        def -=(item: ResourceItem): Unit = children.remove(item.name)

        def -=(itemName: String): Unit = children.remove(itemName)
    }

}

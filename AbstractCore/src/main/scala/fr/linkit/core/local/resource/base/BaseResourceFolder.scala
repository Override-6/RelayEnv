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

package fr.linkit.core.local.resource.base

import fr.linkit.api.local.resource.exception.{IllegalResourceException, IncompatibleResourceTypeException, NoSuchResourceException, ResourceAlreadyPresentException}
import fr.linkit.api.local.resource.external.{ExternalResource, ExternalResourceFactory, ResourceEntry, ResourceFolder}
import fr.linkit.api.local.resource.{ResourceListener, ResourcesMaintainer}
import fr.linkit.api.local.system.fsa.{FileAdapter, FileSystemAdapter}
import fr.linkit.core.local.resource.ResourceFolderMaintainer
import fr.linkit.core.local.resource.local.DefaultResourceEntry
import fr.linkit.core.local.resource.local.LocalResourceFolder.ForbiddenChars
import org.jetbrains.annotations.NotNull

import java.io.File
import scala.collection.mutable
import scala.reflect.{ClassTag, classTag}

abstract class BaseResourceFolder(parent: ResourceFolder, listener: ResourceListener, adapter: FileAdapter) extends AbstractResource(parent, adapter) with ResourceFolder {

    protected val resources                                 = new mutable.HashMap[String, ExternalResource]()
    protected val fsa       : FileSystemAdapter             = adapter.getFSAdapter
    protected val entry     : ResourceEntry[ResourceFolder] = new DefaultResourceEntry[ResourceFolder](this)
    protected val maintainer: ResourceFolderMaintainer      = this.synchronized {
        new ResourceFolderMaintainer(this, listener)
    }

    override def getMaintainer: ResourcesMaintainer = maintainer

    override def getChecksum: Long = resources
            .map(_._2.getChecksum)
            .sum

    override def close(): Unit = {
        resources.foreachEntry((_, resource) => resource.close())
        entry.close()
    }

    override def getEntry: ResourceEntry[ResourceFolder] = entry

    override def getLastChecksum: Long = {
        maintainer.getLastChecksum(name)
    }

    @throws[ResourceAlreadyPresentException]("If the subPath targets a resource that is already registered or opened.")
    @throws[IllegalResourceException]("If the provided name contains invalid character")
    override def openResource[R <: ExternalResource : ClassTag](name: String, factory: ExternalResourceFactory[R]): R = {
        ensureResourceOpenable(name)
        println(s"Opening resource $name, ${classTag[R].runtimeClass}")

        register(name, factory)
    }

    override def isPresentOnDrive(name: String): Boolean = fsa.getAdapter(getAdapter.getAbsolutePath + File.separator + name).exists

    @throws[NoSuchResourceException]("If no resource was found with the provided name")
    @throws[IncompatibleResourceTypeException]("If a resource was found but with another type than R.")
    @NotNull
    override def get[R <: ExternalResource : ClassTag](name: String): R = {
        resources
                .get(name)
                .fold(throw NoSuchResourceException(s"Resource $name not registered in resource folder '$getLocation'")) {
                    case resource: R => resource
                    case other       => throw IncompatibleResourceTypeException(s"Requested resource of type '${classTag[R].runtimeClass.getSimpleName}' but found resource '${other.getClass.getSimpleName}'")
                }
    }

    override def find[R <: ExternalResource : ClassTag](name: String): Option[R] = {
        resources
                .get(name)
                .flatMap {
                    case resource: R => Some(resource)
                    case _           => None
                }
    }

    private def ensureResourceOpenable(name: String): Unit = {
        checkResourceName(name)

        val adapter = fsa.getAdapter(getAdapter.getAbsolutePath + "/" + name)
        if (adapter.exists)
            throw ResourceAlreadyPresentException("The requested resource already exists on this machine's drive.")
    }

    @throws[IllegalResourceException]("If the provided name contains invalid character")
    override def register[R <: ExternalResource](name: String, factory: ExternalResourceFactory[R]): R = this.synchronized {
        checkResourceName(name)

        val resourcePath = getAdapter.getAbsolutePath + File.separator + name
        val adapter      = fsa.getAdapter(resourcePath)
        val resource     = factory(adapter, listener, this)

        resources.put(name, resource)
        //If maintainer is null, that's mean that this method is called during it's initialization.
        //And, therefore the maintainer of this folder have invoked this method.
        //Thus the registration is partially handled by the maintainer and just want
        //the ResourceFolder to add the resource in it's memory.
        //The execution of "maintainer.registerResource(resource)" will be manually handled by the maintainer.
        if (maintainer != null)
            maintainer.registerResource(resource)
        resource
    }

    override def unregister(name: String): Unit = {
        resources -= name
        maintainer.unregisterResource(name)
    }

    protected def checkResourceName(name: String): Unit = {
        name.exists(ForbiddenChars.contains)
    }
}
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

package fr.linkit.api.local.generation.compilation

import java.nio.file.Path

trait CompilationRequestFactory[I, O] {

    val defaultWorkingDirectory: Path

    def makeRequest(context: I, workingDirectory: Path = defaultWorkingDirectory): CompilationRequest[O]

    def makeMultiRequest(contexts: Seq[I], workingDirectory: Path = defaultWorkingDirectory): CompilationRequest[Seq[O]]

}
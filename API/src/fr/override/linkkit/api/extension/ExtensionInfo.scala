package fr.`override`.linkkit.api.`extension`

case class ExtensionInfo(dependencies: Array[String],
                         name: String,
                         extensionClass: Class[_ <: RelayExtension]) {

    val haveNoDependencies: Boolean = dependencies.isEmpty
}
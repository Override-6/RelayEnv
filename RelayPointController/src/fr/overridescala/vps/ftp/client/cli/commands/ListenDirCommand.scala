package fr.overridescala.vps.ftp.client.cli.commands

import fr.overridescala.vps.ftp.api.Relay
import fr.overridescala.vps.ftp.client.auto.{FolderSync, AutomationManager}
import fr.overridescala.vps.ftp.client.cli.{CommandException, CommandExecutor, CommandUtils}
import fr.overridescala.vps.ftp.client.tasks.SyncFoldersTask

class ListenDirCommand(relay: Relay) extends CommandExecutor {


    override def execute(implicit args: Array[String]): Unit = {
        checkArgs(args)
        val target = CommandUtils.argAfter("-t")
        val targetedFolder = CommandUtils.argAfter("-tf")
        val currentFolder = CommandUtils.argAfter("-cf")
        relay.scheduleTask(new SyncFoldersTask(relay, target, targetedFolder, currentFolder))
                .queue()
    }


    def checkArgs(implicit args: Array[String]): Unit = {
        if (args.length != 6)
            throw CommandException("usage : listen -t <target> -tf <target_folder> -cf <current_folder>")
        CommandUtils.checkArgsContains("-t", "-tf", "-cf")
    }

}

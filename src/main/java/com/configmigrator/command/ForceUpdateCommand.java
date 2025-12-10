package com.configmigrator.command;

import com.configmigrator.ConfigMigrator;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import java.util.Collections;
import java.util.List;

public class ForceUpdateCommand extends CommandBase {

    @Override
    public String getName() {
        return "cmforceupdate";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/cmforceupdate - Forces an immediate update of the configs.json file";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("configmigratorupdate");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0; // Allow all players to use (single player friendly)
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Updating config migrations..."));

        try {
            ConfigMigrator.forceUpdate();

            int modifiedCount = ConfigMigrator.getConfigTracker().getModifiedConfigs().size();

            if (modifiedCount > 0) {
                sender.sendMessage(new TextComponentString(
                    TextFormatting.GREEN + "Successfully updated configs.json with " + modifiedCount + " modified config file(s)."));
            } else {
                sender.sendMessage(new TextComponentString(
                    TextFormatting.GREEN + "No config modifications detected from defaults."));
            }
        } catch (Exception e) {
            ConfigMigrator.LOGGER.error("Error during force update", e);
            sender.sendMessage(new TextComponentString(
                TextFormatting.RED + "Error updating configs: " + e.getMessage()));
        }
    }
}

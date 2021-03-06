/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.commands.impl.misc;

import me.lucko.luckperms.common.commands.Arg;
import me.lucko.luckperms.common.commands.CommandResult;
import me.lucko.luckperms.common.commands.abstraction.SingleCommand;
import me.lucko.luckperms.common.commands.sender.Sender;
import me.lucko.luckperms.common.constants.Message;
import me.lucko.luckperms.common.constants.Permission;
import me.lucko.luckperms.common.data.Importer;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.utils.Predicates;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ImportCommand extends SingleCommand {
    private AtomicBoolean running = new AtomicBoolean(false);

    public ImportCommand() {
        super("Import", "Import data from a file", "/%s import <file>", Permission.IMPORT, Predicates.not(1),
                Arg.list(
                        Arg.create("file", true, "the file to import from")
                )
        );
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, List<String> args, String label) {
        if (running.get()) {
            Message.IMPORT_ALREADY_RUNNING.send(sender);
            return CommandResult.STATE_ERROR;
        }

        File f = new File(plugin.getDataDirectory(), args.get(0));
        if (!f.exists()) {
            Message.IMPORT_LOG_DOESNT_EXIST.send(sender, f.getAbsolutePath());
            return CommandResult.INVALID_ARGS;
        }

        Path path = f.toPath();

        if (!Files.isReadable(path)) {
            Message.IMPORT_LOG_NOT_READABLE.send(sender, f.getAbsolutePath());
            return CommandResult.FAILURE;
        }

        List<String> commands;

        try {
            commands = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            Message.IMPORT_LOG_FAILURE.send(sender);
            return CommandResult.FAILURE;
        }

        if (!running.compareAndSet(false, true)) {
            Message.IMPORT_ALREADY_RUNNING.send(sender);
            return CommandResult.STATE_ERROR;
        }

        Importer importer = new Importer(plugin.getCommandManager(), sender, commands);

        // Run the importer in its own thread.
        plugin.doAsync(() -> {
            try {
                importer.run();
            } finally {
                running.set(false);
            }
        });

        return CommandResult.SUCCESS;
    }
}

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

package me.lucko.luckperms.common.commands.impl.generic.other;

import com.google.common.base.Splitter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

import me.lucko.luckperms.common.commands.CommandException;
import me.lucko.luckperms.common.commands.CommandResult;
import me.lucko.luckperms.common.commands.abstraction.SubCommand;
import me.lucko.luckperms.common.commands.sender.Sender;
import me.lucko.luckperms.common.constants.Message;
import me.lucko.luckperms.common.constants.Permission;
import me.lucko.luckperms.common.core.NodeModel;
import me.lucko.luckperms.common.core.PriorityComparator;
import me.lucko.luckperms.common.core.model.Group;
import me.lucko.luckperms.common.core.model.PermissionHolder;
import me.lucko.luckperms.common.core.model.User;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.utils.Predicates;

import io.github.mkremins.fanciful.ChatColor;
import io.github.mkremins.fanciful.FancyMessage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class HolderEditor<T extends PermissionHolder> extends SubCommand<T> {
    public HolderEditor(boolean user) {
        super("editor", "Opens the web permission editor", user ? Permission.USER_EDITOR : Permission.GROUP_EDITOR,
                Predicates.alwaysFalse(), null
        );
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, T holder, List<String> args, String label) throws CommandException {
        JsonObject data = new JsonObject();
        Set<NodeModel> nodes = holder.getNodes().values().stream().map(NodeModel::fromNode).collect(Collectors.toCollection(LinkedHashSet::new));
        data.add("nodes", serializePermissions(nodes));
        data.addProperty("who", id(holder));

        String dataUrl = paste(new GsonBuilder().setPrettyPrinting().create().toJson(data));
        if (dataUrl == null) {
            Message.EDITOR_UPLOAD_FAILURE.send(sender);
            return CommandResult.STATE_ERROR;
        }

        List<String> parts = Splitter.on('/').splitToList(dataUrl);
        String id = parts.get(4) + "/" + parts.get(6);
        String url = "https://lpedit.lucko.me/?" + id;

        Message.EDITOR_URL.send(sender);
        sender.sendMessage(new FancyMessage(url).color(ChatColor.getByChar('b')).link(url));
        return CommandResult.SUCCESS;
    }

    private static String id(PermissionHolder holder) {
        if (holder instanceof User) {
            User user = ((User) holder);
            return "user/" + user.getUuid().toString();
        } else {
            Group group = ((Group) holder);
            return "group/" + group.getName();
        }
    }

    private static String paste(String content) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://api.github.com/gists").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                StringWriter sw = new StringWriter();
                new JsonWriter(sw).beginObject()
                        .name("description").value("LuckPerms Web Permissions Editor Data")
                        .name("public").value(false)
                        .name("files")
                        .beginObject().name("luckperms-data.json")
                        .beginObject().name("content").value(content)
                        .endObject()
                        .endObject()
                        .endObject();

                os.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (connection.getResponseCode() >= 400) {
                throw new RuntimeException("Connection returned response code: " + connection.getResponseCode() + " - " + connection.getResponseMessage());
            }

            try (InputStream inputStream = connection.getInputStream()) {
                try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                    JsonObject response = new Gson().fromJson(reader, JsonObject.class);
                    return response.get("files").getAsJsonObject().get("luckperms-data.json").getAsJsonObject().get("raw_url").getAsString();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static JsonArray serializePermissions(Set<NodeModel> nodes) {
        List<JsonObject> data = new ArrayList<>();

        for (NodeModel node : nodes) {
            JsonObject attributes = new JsonObject();
            attributes.addProperty("permission", node.getPermission());
            attributes.addProperty("value", node.isValue());

            if (!node.getServer().equals("global")) {
                attributes.addProperty("server", node.getServer());
            }

            if (!node.getWorld().equals("global")) {
                attributes.addProperty("world", node.getWorld());
            }

            if (node.getExpiry() != 0L) {
                attributes.addProperty("expiry", node.getExpiry());
            }

            if (!node.getContexts().isEmpty()) {
                attributes.add("context", node.getContextsAsJson());
            }

            data.add(attributes);
        }

        data.sort((o1, o2) -> PriorityComparator.get().compareStrings(
                o1.get("permission").getAsString(),
                o2.get("permission").getAsString()
        ));

        JsonArray arr = new JsonArray();
        for (JsonObject o : data) {
            arr.add(o);
        }

        return arr;
    }
}

package org.woftnw.DreamvisitorHub.data.functions;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.awt.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Infraction {

    public static final String actionBan = "ban"; // regualar MC temp-ban or ban & notify
    public static final String actionUserBan = "user_ban"; // notify only
    public static final String actionNoBan = "no_ban"; // no ban, no notify
    public static final String actionAllBan = "all_ban"; // ban from all
    public static final int BAN_POINT = 3;
    // static final File file = new File(Dreamvisitor.getPlugin().getDataFolder().getPath() + "/infractions.yml");
    private final byte value;
    @NotNull
    private final String reason;
    @NotNull
    private final LocalDateTime time;
    private boolean expired = false;
    @Nullable
    private Long warnChannelID = null;

    public Infraction(byte infractionValue, @NotNull String infractionReason, @NotNull LocalDateTime dateTime) {
        this.value = infractionValue;
        this.reason = infractionReason;
        this.time = dateTime;
    }

    public Infraction(byte infractionValue, @NotNull String infractionReason, @NotNull LocalDateTime dateTime, boolean expired, @Nullable Long warnChannelID) {
        this.value = infractionValue;
        this.reason = infractionReason;
        this.time = dateTime;
        this.expired = expired;
        this.warnChannelID = warnChannelID;
    }

    /**
     * Fetch the infractions of a member from disk.
     *
     * @param memberId the Discord Snowflake ID of the member whose infractions to fetch.
     * @return a non-null {@link List<Infraction>}
     */
    @SuppressWarnings("unchecked")
    public static @NotNull List<Infraction> getInfractions(long memberId) throws FileNotFoundException {

        Yaml yaml = new Yaml();
        InputStream inputStream = new FileInputStream("infractions.yml");
        Map<String, Object> fileData = yaml.load(inputStream);

        List<Map<String, Object>> infractionsMap = (List<Map<String, Object>>) fileData.get(memberId + ".infractions");
        List<Infraction> infractions = new ArrayList<>();
        for (Map<String, Object> map : infractionsMap) infractions.add(deserialize(map));
        return infractions;
    }

    public static @NotNull Map<Long, List<Infraction>> getAllInfractions() throws FileNotFoundException {

        Yaml yaml = new Yaml();
        InputStream inputStream = new FileInputStream("infractions.yml");
        Map<String, Object> fileData = yaml.load(inputStream);

        Set<String> keys = fileData.keySet();
        Map<Long, List<Infraction>> infractionList = new HashMap<>();
        for (String key : keys) {
            List<Map<String, Object>> infractionsMap = (List<Map<String, Object>>) fileData.get(key);
            List<Infraction> infractions = new ArrayList<>();
            for (Map<String, Object> map : infractionsMap) infractions.add(deserialize(map));
            infractionList.put(Long.parseLong(key), infractions);
        }

        return infractionList;
    }

    /**
     * Get the value of all of a member's infractions.
     *
     * @param infractions  the infractions to count.
     * @param countExpired whether to count expired infractions.
     * @return the total value as a {@code byte}.
     */
    @Contract(pure = true)
    public static byte getInfractionCount(@NotNull List<Infraction> infractions, boolean countExpired) {
        byte count = 0;
        for (Infraction infraction : infractions)
            if (countExpired || !infraction.isExpired()) count += infraction.value;
        return count;
    }

    /**
     * Overwrite a member's infraction list with a new list and saves to disk.
     *
     * @param infractions the {@link List<Infraction>} to write.
     * @param memberId    the Discord Snowflake ID of the member to write to.
     */
    public static void setInfractions(@NotNull List<Infraction> infractions, long memberId) throws FileNotFoundException {

        Map<String, Object> data = new HashMap<>();

        List<Map<String, Object>> mapList = new ArrayList<>();
        for (Infraction infraction : infractions) mapList.add(infraction.serialize());
        data.put(memberId + ".infractions", mapList);

        Yaml yaml = new Yaml();
        PrintWriter writer = new PrintWriter("config.yml");
        yaml.dump(data, writer);
        writer.close();

    }

    public static void setTempban(long memberId, boolean state) {
        YamlConfiguration config = getConfig();
        config.set(memberId + ".tempban", state);
        saveToDisk(config);
    }

    public static void setBan(long memberId, boolean state) {
        YamlConfiguration config = getConfig();
        config.set(memberId + ".ban", state);
        saveToDisk(config);
    }

    public static boolean hasTempban(long memberId) {
        return getConfig().getBoolean(memberId + ".tempban");
    }
    public static boolean hasBan(long memberId) {
        return getConfig().getBoolean(memberId + ".ban");
    }


    public static byte getInfractionsUntilBan(long memberId) {
        return (byte) (BAN_POINT - getInfractionCount(getInfractions(memberId), false));
    }

    public static void execute(@NotNull Infraction infraction, @NotNull Member member, boolean silent, @NotNull String actionId) throws InsufficientPermissionException, InvalidObjectException {

        if (!actionId.equals(actionBan) && !actionId.equals(actionAllBan) && !actionId.equals(actionNoBan) && !actionId.equals(actionUserBan))
            throw new InvalidObjectException("Action string does not match any valid actions! Give action ID " + actionId + " does not match possible options: " + actionBan + ", " + actionAllBan + ", " + actionNoBan + ", " + actionUserBan + ".\nSomething has gone very wrong. The infraction has not been recorded.");

        byte infractionsUntilBan = getInfractionsUntilBan(member.getIdLong());

        List<Infraction> infractions = getInfractions(member.getIdLong());
        byte infractionCount = getInfractionCount(infractions, false);

        boolean banPoint;
        banPoint = (infractionCount + infraction.value >= BAN_POINT);

        boolean hasTempban = hasTempban(member.getIdLong());

        byte totalInfractionCount;
        if (!hasTempban) totalInfractionCount = infractionCount;
        else totalInfractionCount = (byte) (infractionCount + BAN_POINT);

        boolean notifyBan = (!actionId.equals(actionNoBan));
        boolean doBan = (actionId.equals(actionBan));
        boolean totalBan = (actionId.equals(actionAllBan));

        if (totalBan) {
            UUID uuid = AccountLink.getUuid(member.getIdLong());
            if (uuid != null) Bukkit.getScheduler().runTask(Dreamvisitor.getPlugin(), bukkitTask -> {
                ProfileBanList banList = Bukkit.getBanList(BanList.Type.PROFILE);
                banList.addBan(Bukkit.createPlayerProfile(uuid), infraction.reason, (Date) null, "Dreamvisitor");
            });
            member.ban(0, TimeUnit.MINUTES).queue();
            return;
        }

        if (doBan) {
            UUID uuid = AccountLink.getUuid(member.getIdLong());
            if (uuid != null) Bukkit.getScheduler().runTask(Dreamvisitor.getPlugin(), bukkitTask -> {
                String username = PlayerUtility.getUsernameOfUuid(uuid);
                if (username != null) {
                    ProfileBanList banList = Bukkit.getBanList(BanList.Type.PROFILE);
                    if (!hasTempban) {
                        LocalDateTime localDateTime = LocalDateTime.now().plusDays(7);
                        banList.addBan(Bukkit.createPlayerProfile(uuid, username), infraction.reason, Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant()), "Dreamvisitor");
                    } else {
                        banList.addBan(Bukkit.createPlayerProfile(uuid, username), infraction.reason, (Date) null, "Dreamvisitor");
                    }
                }
            });
        }

        if (banPoint) {
            infraction.expire();
            List<Infraction> disabledInfractions = new ArrayList<>();
            for (Infraction existingInfraction : infractions) {
                existingInfraction.expire();
                disabledInfractions.add(existingInfraction);
            }
            setInfractions(disabledInfractions, member.getIdLong());
            if (!hasTempban) setTempban(member.getIdLong(), true);
            else setBan(member.getIdLong(), true);
        }

        if (!silent) {

            Category category = Bot.getGameLogChannel().getGuild().getCategoryById(Dreamvisitor.getPlugin().getConfig().getLong("infractions-category-id"));
            if (category == null)
                throw new InvalidObjectException("Category of infractions-category-id is null! The infraction has not been recorded.");
            category.createTextChannel("infraction-" + member.getUser().getName() + "-" + (totalInfractionCount + infraction.value)).queue(channel -> {

                Button primary = Button.primary("warn-understand", "I understand");
                Button secondary = Button.secondary("warn-explain", "I'm confused");

                channel.upsertPermissionOverride(member).setAllowed(Permission.VIEW_CHANNEL).queue();

                EmbedBuilder embed = new EmbedBuilder();

                StringBuilder description = new StringBuilder("You have received an infraction for the following reason:\n");
                description.append("**").append(infraction.reason).append("**\n\n");

                if (infraction.value == 0) description.append("This infraction does not count towards a ban.");
                else {
                    if (infraction.value == 1)
                        description.append("This infraction brings your total count to ").append(infractionCount + infraction.value).append(". ");
                    else
                        description.append("This infraction is worth ").append(infraction.value).append(" warns as opposed to one, bringing your total to ").append(infractionCount + infraction.value).append(". ");

                    if (banPoint) {
                        description.append("This infraction is your third warn within ")
                                .append(Dreamvisitor.getPlugin().getConfig().getInt("infraction-expire-time-days"))
                                .append(" days. ");
                        if (notifyBan) {
                            if (!hasTempban) {
                                if (doBan)
                                    description.append("You will be temporarily banned from the Minecraft server for two weeks. You cannot join until the two weeks has passed.");
                                else
                                    description.append("You will be temporarily banned from the Minecraft server. You cannot join until your temporary ban is over.");
                            } else
                                description.append("You will be permanently banned from the Minecraft server. You cannot rejoin the Minecraft server.");
                        }
                    }
                }

                description.append("\n\nIf you want an explanation for this infraction, press the secondary button below and a staff member will provide more information. Press the primary button to dismiss this message.");

                if (!banPoint) {
                    if (!hasTempban)
                        description.append("\n\n**You do not have a previous temp-ban. You will receive a temp-ban after ").append(infractionsUntilBan - infraction.value).append(" more infractions.**");
                    else
                        description.append("\n\n**You have previously been temp-banned. You will be permanently banned after ").append(infractionsUntilBan - infraction.value).append(" more infractions.**");
                }

                embed.setTitle("Infraction Notice").setDescription(description).setFooter("See the #rules channel for more information about our rules system.").setColor(Color.getHSBColor(17, 100, 100));

                channel.sendMessage(member.getAsMention()).setEmbeds(embed.build()).setActionRow(primary, secondary).queue();
                infraction.warnChannelID = channel.getIdLong();
                infraction.save(member.getIdLong());
            }, throwable -> DCmdWarn.lastInteraction.editOriginal("There was a problem executing this command: " + throwable.getMessage()).queue());

        } else infraction.save(member.getIdLong());

    }

    /**
     * Save an infraction to a member and write to disk.
     *
     * @param memberId the Discord Snowflake ID of the member.
     */
    private void save(long memberId) {
        YamlConfiguration config = getConfig();
        List<Map<?, ?>> mapList = config.getMapList(memberId + ".infractions");
        mapList.add(serialize());
        config.set(memberId + ".infractions", mapList);
        saveToDisk(config);
    }

    public byte getValue() {
        return value;
    }

    public @NotNull String getReason() {
        return reason;
    }

    public @NotNull LocalDateTime getTime() {
        return time;
    }

    public boolean isExpired() {
        expireCheck();
        return expired;
    }

    public void expire() {
        expired = true;
    }

    private void expireCheck() {
        int expireTimeDays = Dreamvisitor.getPlugin().getConfig().getInt("infraction-expire-time-days");
        if (time.plusDays(expireTimeDays).isBefore(LocalDateTime.now())) expired = true;
    }

    @Nullable
    public TextChannel getWarnChannel() {
        if (warnChannelID == null) return null;
        return Bot.getJda().getTextChannelById(warnChannelID);
    }

    public void remind(long user) {
        Dreamvisitor.debug("Remind warn. warnChannelId: " + warnChannelID);
        if (warnChannelID == null) return;
        TextChannel warnChannel = getWarnChannel();
        if (warnChannel == null) return;

        Dreamvisitor.debug("Attempting to retrieve last message.");
        warnChannel.retrieveMessageById(warnChannel.getLatestMessageId()).queue(message -> {
            Dreamvisitor.debug("Retrieved last message.");
            Dreamvisitor.debug("Message author is bot? " + message.getAuthor().equals(Bot.getJda().getSelfUser()));
            Dreamvisitor.debug("Time is passed? " + message.getTimeCreated().plusDays(1).isBefore(OffsetDateTime.now()));
            if (message.getAuthor().equals(Bot.getJda().getSelfUser()) && message.getTimeCreated().plusDays(1).isBefore(OffsetDateTime.now())) {
                warnChannel.getGuild().retrieveMemberById(user).queue(member -> warnChannel.sendMessage(member.getAsMention() + ", you have not yet responded to this thread. On the first message in this thread, press **I understand** to close the thread or **I'm confused** if you're confused.").queue());
            }
        });
    }

    @NotNull
    public Map<String, Object> serialize() {

        Map<String, Object> objectMap = new HashMap<>();
        objectMap.put("value", value);
        objectMap.put("reason", reason);
        objectMap.put("time", time.toString());
        objectMap.put("expired", expired);
        objectMap.put("warnChannelID", warnChannelID);

        return objectMap;
    }

    @Contract("_ -> new")
    public static @NotNull Infraction deserialize(@NotNull Map<String, Object> map) {
        Infraction infraction = new Infraction(Byte.parseByte(String.valueOf((int) map.get("value"))), (String) map.get("reason"), LocalDateTime.parse((CharSequence) map.get("time")));
        if (map.get("expired") != null && (boolean) map.get("expired")) infraction.expire();
        infraction.warnChannelID = (Long) map.get("warnChannelID");
        return infraction;
    }

}

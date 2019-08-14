package net.robinfriedli.botify.command;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.robinfriedli.botify.command.widgets.AbstractWidgetAction;
import net.robinfriedli.botify.discord.MessageService;
import net.robinfriedli.botify.exceptions.UserException;

/**
 * A widget is an interactive message that can be interacted with by adding reactions. Different reactions trigger
 * different actions that are represented by the reaction's emote.
 */
public abstract class AbstractWidget {

    private final CommandManager commandManager;
    private final Message message;
    private final String guildId;
    private final Logger logger;
    private List<AbstractWidgetAction> actions;
    // if the message has been deleted by a widget action that does not always require a reset
    private boolean messageDeleted;

    protected AbstractWidget(CommandManager commandManager, Message message) {
        this.commandManager = commandManager;
        this.message = message;
        this.guildId = message.getGuild().getId();
        logger = LoggerFactory.getLogger(getClass());
    }

    public abstract List<AbstractWidgetAction> setupActions();

    /**
     * Resets (i.e. re-sends) the message output after the widget action has been executed
     */
    public abstract void reset() throws Exception;

    public Message getMessage() {
        return message;
    }

    /**
     * Adds the reactions representing each available action to the message and prepares all actions
     */
    public void setup() {
        this.actions = setupActions();
        try {
            CompletableFuture<RuntimeException> futureException = new CompletableFuture<>();
            for (int i = 0; i < actions.size(); i++) {
                AbstractWidgetAction action = actions.get(i);
                int finalI = i;
                message.addReaction(action.getEmojiUnicode()).queue(aVoid -> {
                    if (finalI == actions.size() - 1 && !futureException.isDone()) {
                        futureException.cancel(false);
                    }
                }, throwable -> {
                    if (throwable instanceof InsufficientPermissionException || throwable instanceof ErrorResponseException) {
                        if (!futureException.isDone()) {
                            futureException.complete((RuntimeException) throwable);
                        }
                    } else {
                        logger.warn("Unexpected exception while adding reaction", throwable);
                    }
                });
            }

            futureException.thenAccept(e -> {
                MessageService messageService = new MessageService();
                if (e instanceof InsufficientPermissionException) {
                    messageService.sendError("Bot is missing permission: "
                        + ((InsufficientPermissionException) e).getPermission().getName(), message.getChannel());
                } else if (e instanceof ErrorResponseException) {
                    if (((ErrorResponseException) e).getErrorCode() == 50013) {
                        messageService.sendError("Bot is missing permission to add reactions", message.getChannel());
                    } else {
                        logger.warn("Could not add reaction to message " + message, e);
                    }
                }
            });
        } catch (InsufficientPermissionException e) {
            // exception is actually never thrown when it should be, remove completable future hack if this ever changes
            throw new UserException("Bot is missing permission: " + e.getPermission().getName(), e);
        } catch (ErrorResponseException e) {
            if (e.getErrorCode() == 50013) {
                throw new UserException("Bot is missing permission to add reactions");
            } else {
                logger.warn("Could not add reaction to message " + message, e);
            }
        }
    }

    public void destroy() {
        commandManager.removeWidget(this);
        if (!isMessageDeleted()) {
            try {
                try {
                    message.delete().queue();
                } catch (InsufficientPermissionException ignored) {
                    message.clearReactions().queue();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void handleReaction(GuildMessageReactionAddEvent event) throws Exception {
        MessageReaction reaction = event.getReaction();
        User sourceUser = event.getUser();
        Optional<AbstractWidgetAction> actionOptional = actions.stream()
            .filter(widgetAction -> widgetAction.getEmojiUnicode().equals(reaction.getReactionEmote().getName()))
            .findAny();
        if (actionOptional.isPresent()) {
            AbstractWidgetAction widgetAction = actionOptional.get();
            widgetAction.run(event);
            if (widgetAction.isResetRequired()) {
                reset();
            } else if (!isMessageDeleted()) {
                try {
                    reaction.removeReaction(sourceUser).queue();
                } catch (InsufficientPermissionException e) {
                    throw new UserException("Bot is missing permission: " + e.getPermission().getName());
                }
            }
        }
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public boolean isMessageDeleted() {
        return messageDeleted;
    }

    public void setMessageDeleted(boolean messageDeleted) {
        this.messageDeleted = messageDeleted;
    }

    public String getGuildId() {
        return guildId;
    }

}

use std::sync::Arc;
use std::sync::mpsc::Sender;

use contract::DiscordUserId;
use incident::{Event, Notice};
use serenity::all::{
    ActionRowComponent, ButtonStyle, ChannelId, ComponentInteraction, Context, CreateActionRow,
    CreateAllowedMentions, CreateButton, CreateInputText, CreateInteractionResponse,
    CreateInteractionResponseMessage, CreateMessage, CreateModal, EventHandler, GatewayIntents,
    Http, InputTextStyle, Interaction as GatewayInteraction, MessageFlags, ModalInteraction,
};
use serenity::async_trait;
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender};

use crate::core::Chat;
use crate::custom_id::CustomId;
use crate::discord_interact::{Decision, Interaction, Reply, decide};
use crate::discord_render::{Button, Message, render};
use crate::telemetry::Telemetry;

pub(crate) struct DiscordChat {
    notices: UnboundedSender<Notice>,
}

impl DiscordChat {
    pub(crate) fn new(notices: UnboundedSender<Notice>) -> Self {
        Self { notices }
    }
}

impl Chat for DiscordChat {
    fn post(&self, notice: &Notice) {
        if self.notices.send(notice.clone()).is_err() {
            eprintln!("discord notice dropped: channel closed");
        }
    }
}

struct Handler {
    owner: DiscordUserId,
    events: Sender<Event>,
}

fn button(button: &Button) -> CreateButton {
    let style = if button.danger {
        ButtonStyle::Danger
    } else {
        ButtonStyle::Primary
    };
    CreateButton::new(button.custom_id.to_string())
        .label(&button.label)
        .style(style)
}

fn message(message: &Message) -> CreateMessage {
    let created = CreateMessage::new()
        .content(&message.text)
        .flags(MessageFlags::SUPPRESS_EMBEDS)
        .allowed_mentions(CreateAllowedMentions::new());
    if message.buttons.is_empty() {
        return created;
    }
    created.components(vec![CreateActionRow::Buttons(
        message.buttons.iter().map(button).collect(),
    )])
}

fn modal(id: &CustomId, title: &str, input: CreateInputText) -> CreateInteractionResponse {
    CreateInteractionResponse::Modal(
        CreateModal::new(id.to_string(), title).components(vec![CreateActionRow::InputText(input)]),
    )
}

fn response(reply: Reply) -> CreateInteractionResponse {
    match reply {
        Reply::AskCode(id) => {
            let input = CreateInputText::new(InputTextStyle::Short, "TOTP 6자리", "value")
                .min_length(6)
                .max_length(6);
            modal(&id, "조치 승인", input)
        }
        Reply::AskActualCause(id) => {
            let input = CreateInputText::new(InputTextStyle::Paragraph, "실제 원인", "value")
                .max_length(500);
            modal(&id, "실제 원인", input)
        }
        Reply::Reject(text) | Reply::Accepted(text) => CreateInteractionResponse::Message(
            CreateInteractionResponseMessage::new()
                .content(text)
                .ephemeral(true),
        ),
    }
}

impl Handler {
    fn dispatch(&self, interaction: &Interaction) -> CreateInteractionResponse {
        let Decision { reply, event } = decide(&self.owner, interaction);
        if let Some(event) = event
            && self.events.send(event).is_err()
        {
            eprintln!("discord event dropped: core stopped");
        }
        response(reply)
    }

    async fn component(&self, ctx: &Context, component: ComponentInteraction) {
        let interaction = Interaction {
            user_id: component.user.id.to_string(),
            custom_id: component.data.custom_id.clone(),
            submitted: None,
        };
        let reply = self.dispatch(&interaction);
        if component.create_response(&ctx.http, reply).await.is_err() {
            eprintln!("discord response failed");
        }
    }

    async fn submitted(&self, ctx: &Context, modal: ModalInteraction) {
        let value = modal
            .data
            .components
            .iter()
            .flat_map(|row| &row.components)
            .find_map(|component| match component {
                ActionRowComponent::InputText(input) => input.value.clone(),
                _ => None,
            })
            .unwrap_or_default();
        let interaction = Interaction {
            user_id: modal.user.id.to_string(),
            custom_id: modal.data.custom_id.clone(),
            submitted: Some(value),
        };
        let reply = self.dispatch(&interaction);
        if modal.create_response(&ctx.http, reply).await.is_err() {
            eprintln!("discord response failed");
        }
    }
}

#[async_trait]
impl EventHandler for Handler {
    async fn interaction_create(&self, ctx: Context, interaction: GatewayInteraction) {
        match interaction {
            GatewayInteraction::Component(component) => self.component(&ctx, component).await,
            GatewayInteraction::Modal(modal) => self.submitted(&ctx, modal).await,
            _ => {}
        }
    }
}

async fn post_notices(
    http: Arc<Http>,
    channel: ChannelId,
    (grafana_url, telemetry): (Option<String>, Arc<Telemetry>),
    mut notices: UnboundedReceiver<Notice>,
) {
    while let Some(notice) = notices.recv().await {
        let rendered = render(&notice, grafana_url.as_deref());
        if let Err(error) = channel.send_message(&http, message(&rendered)).await {
            telemetry.add("incident_agent_discord_failures_total", &[], 1.0);
            eprintln!("discord post failed: {error}");
        }
    }
}

pub(crate) struct Link {
    pub(crate) token: String,
    pub(crate) channel_id: u64,
    pub(crate) owner: DiscordUserId,
    pub(crate) grafana_url: Option<String>,
    pub(crate) telemetry: Arc<Telemetry>,
}

pub(crate) async fn run(
    link: Link,
    events: Sender<Event>,
    notices: UnboundedReceiver<Notice>,
) -> Result<(), String> {
    let handler = Handler {
        owner: link.owner,
        events,
    };
    let mut client = serenity::Client::builder(&link.token, GatewayIntents::empty())
        .event_handler(handler)
        .await
        .map_err(|_| "discord client could not be built".to_owned())?;
    tokio::spawn(post_notices(
        Arc::clone(&client.http),
        ChannelId::new(link.channel_id),
        (link.grafana_url, link.telemetry),
        notices,
    ));
    client
        .start()
        .await
        .map_err(|_| "discord gateway stopped".to_owned())
}

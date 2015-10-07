package org.lskk.lumen.speech.expression;

import com.google.common.base.Preconditions;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.language.HeaderExpression;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.joda.time.DateTime;
import org.lskk.lumen.core.AudioObject;
import org.lskk.lumen.core.CommunicateAction;
import org.lskk.lumen.core.LumenThing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Locale;
import java.util.Optional;

@Component
@Profile("speechExpressionApp")
public class SpeechExpressionRouter extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(SpeechExpressionRouter.class);
    public static final int SAMPLE_RATE = 16000;
    public static final String FLAC_TYPE = "audio/x-flac";
    private static final DefaultExecutor executor = new DefaultExecutor();

    @Inject
    private Environment env;
    @Inject
    private ToJson toJson;
    @Inject
    private ProducerTemplate producer;

    @Override
    public void configure() throws Exception {
        final String ffmpegExecutable = !new File("/usr/bin/ffmpeg").exists() && new File("/usr/bin/avconv").exists() ? "avconv" : "ffmpeg";
        log.info("libav autodetection result: We will use '{}'", ffmpegExecutable);
        from("rabbitmq://localhost/amq.topic?connectionFactory=#amqpConnFactory&exchangeType=topic&autoDelete=false&routingKey=lumen.speech.expression")
                .to("log:IN.lumen.speech.expression?showHeaders=true&showAll=true&multiline=true")
                .process(exchange -> {
                    final LumenThing thing = toJson.getMapper().readValue(
                            exchange.getIn().getBody(byte[].class), LumenThing.class);
                    if (thing instanceof CommunicateAction) {
                        final CommunicateAction communicateAction = (CommunicateAction) thing;
                        final Locale lang = Optional.ofNullable(communicateAction.getInLanguage()).orElse(Locale.US);
                        log.info("Got speech lang-legacy={}: {}", lang.getLanguage(), communicateAction);
                        final String avatarId = Optional.ofNullable(communicateAction.getAvatarId()).orElse("nao1");

                        final File wavFile = File.createTempFile("lumen-speech-expression_", ".wav");
                        final File oggFile = File.createTempFile("lumen-speech-expression_", ".ogg");
                        try {

                            try (final ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                                final CommandLine cmdLine = new CommandLine("espeak");
                                cmdLine.addArgument("-s");
                                cmdLine.addArgument("130");
                                if ("in".equals(lang.getLanguage())) {
                                    cmdLine.addArgument("-v");
                                    cmdLine.addArgument("mb-id1");
                                } else if ("ar".equals(lang.getLanguage())) {
                                    cmdLine.addArgument("-v");
                                    cmdLine.addArgument("mb-ar1");
                                }
                                cmdLine.addArgument("-w");
                                cmdLine.addArgument(wavFile.toString());
                                cmdLine.addArgument(communicateAction.getObject());
                                executor.setStreamHandler(new PumpStreamHandler(bos));
                                final int executed;
                                try {
                                    executed = executor.execute(cmdLine);
                                } finally {
                                    log.info("{}: {}", cmdLine, bos.toString());
                                }
                            }

                            try (final ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                                // flac.exe doesn't support mp3, and that's a problem for now (note: mp3 patent is expiring)
                                final CommandLine cmdLine = new CommandLine(ffmpegExecutable);
                                cmdLine.addArgument("-i");
                                cmdLine.addArgument(wavFile.toString());
                                cmdLine.addArgument("-ar");
                                cmdLine.addArgument(String.valueOf(SAMPLE_RATE));
                                cmdLine.addArgument("-ac");
                                cmdLine.addArgument("1");
                                cmdLine.addArgument("-y"); // happens, weird!
                                cmdLine.addArgument(oggFile.toString());
                                executor.setStreamHandler(new PumpStreamHandler(bos));
                                final int executed;
                                try {
                                    executed = executor.execute(cmdLine);
                                } finally {
                                    log.info("{}: {}", cmdLine, bos.toString());
                                }
                                Preconditions.checkState(oggFile.exists(), "Cannot convert %s to OGG %s",
                                        wavFile, oggFile);

                                // Send
                                final byte[] audioContent = FileUtils.readFileToByteArray(oggFile);
                                final String audioContentType = "audio/ogg";

                                final AudioObject audioObject = new AudioObject();
                                audioObject.setContentType(audioContentType + "; rate=" + SAMPLE_RATE);
                                audioObject.setContentUrl("data:" + audioContentType + ";base64," + Base64.encodeBase64String(audioContent));
                                audioObject.setContentSize((long) audioContent.length);
                                audioObject.setName(FilenameUtils.getName(oggFile.getName()));
                                audioObject.setDateCreated(new DateTime());
                                audioObject.setDatePublished(audioObject.getDateCreated());
                                audioObject.setDateModified(audioObject.getDateCreated());
                                audioObject.setUploadDate(audioObject.getDateCreated());
                                final String audioOutUri = "rabbitmq://dummy/amq.topic?connectionFactory=#amqpConnFactory&exchangeType=topic&autoDelete=false&routingKey=avatar." + avatarId + ".audio.out";
                                log.info("Sending {} to {} ...", audioObject, audioOutUri);
                                producer.sendBody(audioOutUri, toJson.mapper.writeValueAsBytes(audioObject));
                            }
                        } finally {
                            oggFile.delete();
                            wavFile.delete();
                        }

                        // reply
                        exchange.getOut().setBody("{}");
                        final String replyTo = exchange.getIn().getHeader("rabbitmq.REPLY_TO", String.class);
                        if (replyTo != null) {
                            log.debug("Sending reply to {} ...", replyTo);
                            exchange.getOut().setHeader("rabbitmq.ROUTING_KEY", replyTo);
                            exchange.getOut().setHeader("rabbitmq.EXCHANGE_NAME", "");
                            exchange.getOut().setHeader("recipients",
                                    "rabbitmq://dummy/dummy?connectionFactory=#amqpConnFactory&autoDelete=false,log:OUT.lumen.speech.expression");
                        } else {
                            exchange.getOut().setHeader("recipients", "log:OUT.lumen.speech.expression");
                        }
                    }
                })
                .routingSlip(new HeaderExpression("recipients"));
    }
}

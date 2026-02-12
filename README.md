# Sticker Bot

An Android tool to use telegram stickers everywhere

![](./preview.webp)

## Q & A

- Q: How to get a telegram bot token? \
  A: You can create a bot and generate tokens from [`@botfather`](https://core.telegram.org/bots/features#botfather)
- Q: Why are the transparent areas incorrect for video stickers after converting to GIF? \
  A: The current WebM decoding relies on the Android system's built-in codec, which does not support the VP9 format with an alpha channel; at the same time, decoding may produce other glitches on older or modified Android systems.
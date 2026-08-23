// Builds the "instructions" string sent to OpenAI's gpt-4o-mini-tts on every call —
// tone/delivery guidance layered on top of the fixed voice, driven by who's being
// addressed, how urgent the moment is (Context Engine), and time of day.
export type VoiceInstructionsContext = {
  speaker?: string;
  urgency?: 'low' | 'normal' | 'high';
  timeOfDay?: 'morning' | 'afternoon' | 'evening' | 'night';
};

export function currentTimeOfDay(hour: number = new Date().getHours()): 'morning' | 'afternoon' | 'evening' | 'night' {
  if (hour < 6)  return 'night';
  if (hour < 12) return 'morning';
  if (hour < 18) return 'afternoon';
  if (hour < 22) return 'evening';
  return 'night';
}

export function buildVoiceInstructions(ctx: VoiceInstructionsContext = {}): string {
  if (ctx.urgency === 'high') {
    return 'Vorbește rapid, clar, fără ezitări.';
  }
  if (ctx.speaker && ctx.speaker.toLowerCase() === 'hannah') {
    return 'Vorbește simplu, cald, prietenos.';
  }
  if (ctx.timeOfDay === 'night') {
    return 'Ton cald și discret, cu voce mai joasă — e noapte târziu.';
  }
  return 'Ton cald, conversațional, natural, cu pauze.';
}

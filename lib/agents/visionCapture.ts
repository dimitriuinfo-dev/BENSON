// Photo/video capture + AI vision analysis (product-owner-directed 2026-09-18) — camera/gallery
// buttons on the main screen and the overlay bubble. Opens the OS's own camera/gallery UI (same
// "deep link to the OS app" idiom already used for Waze/WhatsApp elsewhere in this codebase)
// rather than building a custom in-app camera.

import * as ImagePicker from 'expo-image-picker';
import * as VideoThumbnails from 'expo-video-thumbnails';
import { File } from 'expo-file-system';
import { analyzeImageWithClaude } from './claudeAgent';

export type CaptureMode = 'photo' | 'video';
export type CaptureResult = { uri: string; isVideo: boolean; mimeType?: string };

async function ensureCameraPermission(): Promise<boolean> {
  const perm = await ImagePicker.getCameraPermissionsAsync();
  if (perm.granted) return true;
  return (await ImagePicker.requestCameraPermissionsAsync()).granted;
}

async function ensureLibraryPermission(): Promise<boolean> {
  const perm = await ImagePicker.getMediaLibraryPermissionsAsync();
  if (perm.granted) return true;
  return (await ImagePicker.requestMediaLibraryPermissionsAsync()).granted;
}

// Camera button — captures exactly one photo or video, per the current toggle mode.
export async function capture(mode: CaptureMode): Promise<CaptureResult | null> {
  if (!(await ensureCameraPermission())) return null;
  const result = await ImagePicker.launchCameraAsync({
    mediaTypes: mode === 'video' ? ['videos'] : ['images'],
    quality: 0.7,
    videoMaxDuration: 30,
  });
  if (result.canceled || !result.assets?.[0]) return null;
  const asset = result.assets[0];
  return { uri: asset.uri, isVideo: asset.type === 'video', mimeType: asset.mimeType };
}

// "+" button — pick an existing photo or video from the gallery.
export async function pickFromLibrary(): Promise<CaptureResult | null> {
  if (!(await ensureLibraryPermission())) return null;
  const result = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ['images', 'videos'],
    quality: 0.7,
  });
  if (result.canceled || !result.assets?.[0]) return null;
  const asset = result.assets[0];
  return { uri: asset.uri, isVideo: asset.type === 'video', mimeType: asset.mimeType };
}

// Claude's vision API only accepts still images, never a video file. For a video, extract one
// representative frame (1s in) and analyze that — the spoken reply says so explicitly (see
// analyzeCapturedMedia's default question below), never claiming full video understanding this
// can't actually do.
async function toAnalyzableImage(
  uri: string, isVideo: boolean, mimeType?: string,
): Promise<{ uri: string; mimeType: string } | null> {
  if (!isVideo) return { uri, mimeType: mimeType || 'image/jpeg' };
  try {
    const { uri: thumbUri } = await VideoThumbnails.getThumbnailAsync(uri, { time: 1000, quality: 0.7 });
    return { uri: thumbUri, mimeType: 'image/jpeg' };
  } catch {
    return null;
  }
}

export async function analyzeCapturedMedia(params: {
  uri: string;
  isVideo: boolean;
  mimeType?: string;
  apiKey: string;
  lang: string;
  question?: string;
}): Promise<string | null> {
  if (!params.apiKey) return null;
  const img = await toAnalyzableImage(params.uri, params.isVideo, params.mimeType);
  if (!img) return null;
  const file = new File(img.uri.startsWith('file://') ? img.uri : `file://${img.uri}`);
  const base64Data = await file.base64();
  const question = params.question ?? (params.isVideo
    ? 'Descrie ce se vede în acest cadru extras din videoclip (nu ai văzut tot clipul, doar un cadru).'
    : 'Descrie ce vezi în această poză.');
  return analyzeImageWithClaude({
    apiKey: params.apiKey,
    base64Data,
    mediaType: img.mimeType,
    question,
    lang: params.lang,
  });
}

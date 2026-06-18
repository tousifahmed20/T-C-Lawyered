/**
 * Google Gemini generateContent adapter. Conforms to the shared provider
 * interface. Gemini has no separate system role, so the system prompt is sent
 * via systemInstruction and JSON output is forced via responseMimeType.
 */
import { PROVIDER_ENDPOINTS } from '../../utils/CONSTANTS.js';
import { providerError } from '../llm.js';

export async function call({ systemPrompt, userPrompt, model, apiKey, signal }) {
  const url = `${PROVIDER_ENDPOINTS.gemini}/${model}:generateContent?key=${encodeURIComponent(apiKey)}`;
  const res = await fetch(url, {
    method: 'POST',
    signal,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: systemPrompt }] },
      contents: [{ role: 'user', parts: [{ text: userPrompt }] }],
      generationConfig: { responseMimeType: 'application/json', maxOutputTokens: 2048 },
    }),
  });

  if (!res.ok) throw providerError(res.status, await safeBody(res));

  const data = await res.json();
  const text = (data.candidates?.[0]?.content?.parts || [])
    .map((part) => part.text || '')
    .join('');
  const tokensUsed = data.usageMetadata?.totalTokenCount || 0;
  return { text, tokensUsed };
}

async function safeBody(res) {
  try {
    return JSON.stringify(await res.json());
  } catch {
    return res.statusText;
  }
}

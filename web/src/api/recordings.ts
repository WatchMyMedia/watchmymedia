import { api, unwrap } from './client'
import type { components, paths } from './schema'
export type Recording = components['schemas']['LiveTVRecording']
export type RecordingRule = components['schemas']['LiveTVRecordingRule']
export type RecordingSettings = components['schemas']['LiveTVRecordingSettings']
export type CreateRecording = paths['/api/live-tv/recordings']['post']['requestBody']['content']['application/json']
export type ManualRecording = paths['/api/live-tv/recordings/manual']['post']['requestBody']['content']['application/json']
export type UpdateRule = paths['/api/live-tv/recording-rules/{id}']['patch']['requestBody']['content']['application/json']
export const createRecording = (body: CreateRecording) => api.POST('/api/live-tv/recordings', { body }).then(unwrap)
export const createManualRecording = (body: ManualRecording) => api.POST('/api/live-tv/recordings/manual', { body }).then(unwrap)
export const cancelRecording = (id: string) => api.DELETE('/api/live-tv/recordings/{id}', { params: { path: { id } } }).then(unwrap)
export type ConflictRef = components['schemas']['LiveTVConflictRef']
/** With `over`, that clashing recording gives way and every other keeps its tuner. */
export const preferRecording = ({ id, over }: { id: string; over?: string }) => api.PATCH('/api/live-tv/recordings/{id}', { params: { path: { id } }, body: { prefer: true, ...(over ? { over } : {}) } }).then(unwrap)
export const updateRecording = (id: string, body: { padBefore?: number; padAfter?: number }) => api.PATCH('/api/live-tv/recordings/{id}', { params: { path: { id } }, body }).then(unwrap)
export const updateRecordingRule = (id: string, body: UpdateRule) => api.PATCH('/api/live-tv/recording-rules/{id}', { params: { path: { id } }, body }).then(unwrap)
export const deleteRecordingRule = (id: string) => api.DELETE('/api/live-tv/recording-rules/{id}', { params: { path: { id } } }).then(unwrap)
export const saveRecordingSettings = (body: RecordingSettings) => api.PUT('/api/settings/live-tv/recordings', { body }).then(unwrap)

export const deleteRecordingFile = ({ id, recordAgain }: { id: string; recordAgain: boolean }) => api.DELETE('/api/live-tv/recordings/{id}/file', { params: { path: { id }, query: { recordAgain } } }).then(unwrap)

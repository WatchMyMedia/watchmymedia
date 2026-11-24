// The make of TV this build is for. Type checks and tests see Samsung's; each
// build replaces this module with its own make's platform (`tvPlatform` in
// ../../vite.tv.config.ts), so shared code imports `platform` from here and
// never from a make's folder.
export { platform } from './samsung'

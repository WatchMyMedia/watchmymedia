import { describe, expect, it } from 'vitest'
import { cueText, parseVtt, vttTime } from './vtt'

describe('WebVTT', () => {
  it('reads timestamps with and without hours', () => {
    expect(vttTime('00:01.500')).toBe(1_500)
    expect(vttTime('01:02:03.004')).toBe(3_723_004)
    expect(vttTime('00:00:01,250')).toBe(1_250)
    expect(vttTime('1:5.000')).toBeUndefined()
    expect(vttTime('nope')).toBeUndefined()
  })

  it('parses cues, skipping the header, notes, styles and broken cues', () => {
    const cues = parseVtt([
      '\uFEFFWEBVTT - a title', 'Kind: captions', '',
      'NOTE a comment', 'over two lines', '',
      'STYLE', '::cue { color: yellow }', '',
      'intro', '00:00:01.000 --> 00:00:03.000 align:center', '<v Narrator>Hello &amp; <i>welcome</i></v>', 'second line', '',
      '00:00:02.500 --> 00:00:04.000 line:0', 'Top', '',
      '00:00:09.000 --> 00:00:08.000', 'ends before it starts', '',
      '00:00:05.000 --> 00:00:06.000', '<b></b>', '',
      '00:00:07.000 --> 00:00:08.000 line:90%', 'Bottom &#x263A;',
    ].join('\r\n'))
    expect(cues).toEqual([
      { id: 0, startMs: 1000, endMs: 3000, lines: ['Hello & welcome', 'second line'], top: false },
      { id: 1, startMs: 2500, endMs: 4000, lines: ['Top'], top: true },
      { id: 2, startMs: 7000, endMs: 8000, lines: ['Bottom ☺'], top: false },
    ])
  })

  it('sorts cues by start', () => {
    expect(parseVtt('WEBVTT\n\n00:05.000 --> 00:06.000\nB\n\n00:01.000 --> 00:02.000\nA').map((c) => c.lines[0])).toEqual(['A', 'B'])
  })

  it('takes out markup and reads entities', () => {
    expect(cueText('<c.yellow>a</c> &lt;b&gt; &nbsp;<00:00:01.000>c &bogus;')).toBe('a <b> \u00a0c &bogus;')
  })
})

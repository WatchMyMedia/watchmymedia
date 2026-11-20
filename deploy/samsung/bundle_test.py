"""The check that a built Samsung TV app loads in the TV's Chromium 108."""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('bundle.py')
spec = importlib.util.spec_from_file_location('samsung_bundle', SCRIPT)
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)

# As pnpm build:tizen writes it.
PAGE = ('<!doctype html>\n<html lang="en">\n<head>\n  <script defer src="./assets/index-a1.js"></script>\n'
        '  <link rel="stylesheet" href="./assets/style-b2.css">\n</head>\n<body><div id="root"></div></body>\n</html>\n')


class PageTest(unittest.TestCase):
    def test_one_classic_deferred_script_passes(self):
        self.assertEqual([], bundle.page_problems(PAGE))
        # The words alone, outside a tag's attributes, are not the attributes.
        self.assertEqual([], bundle.page_problems(PAGE.replace('<div', '<p>No module, no crossorigin</p><div')))

    def test_module_scripts_and_crossorigin_fail(self):
        for html in ('<script type="module" src="./a.js"></script>', "<script defer type='module' src=a.js>",
                     '<script type=module src=a.js></script>'):
            with self.subTest(html=html):
                self.assertEqual(['loads a module script: build it as one classic deferred script'],
                                 bundle.page_problems(html))
        for html in ('<script defer crossorigin src="./a.js"></script>', '<link rel="stylesheet" crossorigin href="a.css">',
                     '<script crossorigin="anonymous" src=a.js></script>', '<link crossorigin/>'):
            with self.subTest(html=html):
                self.assertEqual(['asks for crossorigin, which file:// can refuse'], bundle.page_problems(html))


class ScriptTest(unittest.TestCase):
    def test_what_chrome_108_has_passes(self):
        script = ('(function(){const a=[3,1,2];a.at(-1);a.findLast(Boolean);Object.hasOwn(a,"x");structuredClone(a);'
                  '"x".replaceAll("x","y");a.sort();a.reverse();a.splice(0,1);new Set(a).has(1);'
                  'const union={},o={difference:1};o.difference;union.size})()')
        self.assertEqual([], bundle.script_problems(script))

    def test_each_method_newer_than_chrome_108_fails(self):
        for script, use, version in (
                ('a.toSorted()', '.toSorted', 110), ('a?.toReversed()', '.toReversed', 110),
                ('a.toSpliced(0,1)', '.toSpliced', 110), ('s.isWellFormed()', '.isWellFormed', 111),
                ('s.toWellFormed()', '.toWellFormed', 111), ('Object.groupBy(a,f)', 'Object.groupBy', 117),
                ('Map.groupBy(a,f)', 'Map.groupBy', 117), ('const{promise:p}=Promise.withResolvers()', 'Promise.withResolvers', 119),
                ('await Array.fromAsync(g)', 'Array.fromAsync', 121), ('a.union(b)', '.union(', 122),
                ('a.intersection(b)', '.intersection(', 122), ('a.difference(b)', '.difference(', 122),
                ('a.symmetricDifference(b)', '.symmetricDifference(', 122), ('a.isSubsetOf(b)', '.isSubsetOf(', 122),
                ('a.isSupersetOf(b)', '.isSupersetOf(', 122), ('a.isDisjointFrom(b)', '.isDisjointFrom(', 122)):
            with self.subTest(script=script):
                self.assertEqual([f'uses {use}, from Chrome {version}'], bundle.script_problems(f'(()=>{{{script}}})()'))

    def test_import_meta_fails_and_each_use_is_named_once(self):
        self.assertEqual(['uses import.meta, which a classic script cannot hold'],
                         bundle.script_problems('console.log(import.meta.url)'))
        self.assertEqual(['uses .toSorted, from Chrome 110'], bundle.script_problems('a.toSorted();b.toSorted()'))


class StyleTest(unittest.TestCase):
    def test_what_chrome_108_has_passes(self):
        style = ('@charset "utf-8";@layer base,app;:root{color-scheme:dark;--accent:#ffb612}a{color:rgb(1 2 3/.5)}'
                 '@media (min-width:1px){a{color:red}@supports (display:grid){b{display:grid}}}'
                 '@keyframes k{from{opacity:0}50%{opacity:.5}to{opacity:1}}@font-face{font-family:x;src:url(x.woff2)}'
                 '@page{margin:1cm;@top-left{content:"x"}}@layer app{c{color:#fff}}.x:has(>b){color:#000}'
                 # The build writes a colour oklch() named twice, and Chrome 108 keeps the first.
                 '.y{color:#d58679;color:lab(64.3% 30.07 20.47)}')
        self.assertEqual([], bundle.style_problems(style))

    def test_colour_functions_newer_than_chrome_108_fail(self):
        for value, use, version in (('color-mix(in srgb,var(--accent),red)', 'color-mix(', 111),
                                    ('oklch(.7 .1 var(--hue))', 'oklch(', 111), ('OKLAB(.7 .1 .1)', 'OKLAB(', 111),
                                    ('light-dark(var(--a),var(--b))', 'light-dark(', 123)):
            with self.subTest(value=value):
                self.assertEqual([f'uses {use}, from Chrome {version}'], bundle.style_problems(f'a{{color:{value}}}'))

    def test_nested_rules_fail(self):
        for style, prelude in (('a{color:red;b{color:blue}}', 'b'), ('.x{&:hover{color:red}}', '&:hover'),
                               ('a{@media (min-width:1px){color:red}}', '@media (min-width:1px)'),
                               ('@media print{a{color:red;.b{color:blue}}}', '.b'),
                               ('@keyframes k{from{opacity:0;b{c:d}}}', 'b')):
            with self.subTest(style=style):
                self.assertEqual([f'nests {prelude}{{ in another rule, from Chrome 112'], bundle.style_problems(style))

    def test_comments_and_strings_are_not_read_as_css(self):
        self.assertEqual([], bundle.style_problems('/*! color-mix( a{b{ */a{content:"oklch( {";quotes:\'light-dark(\' "}"}'))
        # A name that only ends like one of the functions is another name.
        self.assertEqual([], bundle.style_problems('a{animation:my-oklch(1);b:xcolor-mix(1)}'))


class AppTest(unittest.TestCase):
    def app(self, files):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = pathlib.Path(folder.name)
        for name, text in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(text)
        return root

    BUILT = {'index.html': PAGE, 'config.xml': '<widget/>', 'icon.png': 'png', 'assets/index-a1.js': '(()=>{})()',
             'assets/style-b2.css': 'a{color:red}'}

    def run_script(self, app):
        return subprocess.run([sys.executable, '-I', str(SCRIPT), str(app)], capture_output=True, text=True)

    def test_a_built_app_passes(self):
        app = self.app(self.BUILT)
        self.assertEqual([], bundle.problems(app))
        result = self.run_script(app)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('assets/index-a1.js, assets/style-b2.css, config.xml, icon.png, index.html', result.stdout)

    def test_missing_files_and_split_bundles_fail(self):
        files = dict(self.BUILT, **{'assets/chunk-c3.js': '(()=>{})()'})
        del files['icon.png'], files['assets/style-b2.css']
        self.assertEqual(['icon.png is missing', 'should hold one script and holds 2: assets/chunk-c3.js, assets/index-a1.js',
                          'should hold one stylesheet and holds 0'], bundle.problems(self.app(files)))
        # The core-only bundle is not an app.
        self.assertEqual(['index.html is missing', 'config.xml is missing', 'icon.png is missing',
                          'should hold one stylesheet and holds 0'], bundle.problems(self.app({'tizen-core.js': 'export {}'})))

    def test_each_file_is_checked_by_its_kind(self):
        app = self.app(dict(self.BUILT, **{'index.html': PAGE.replace('<script defer', '<script type="module"'),
                                           'assets/index-a1.js': 'a.toSorted()', 'assets/style-b2.css': 'a{b{c:d}}'}))
        self.assertEqual(['index.html loads a module script: build it as one classic deferred script',
                          'assets/index-a1.js uses .toSorted, from Chrome 110',
                          'assets/style-b2.css nests b{ in another rule, from Chrome 112'], bundle.problems(app))
        result = self.run_script(app)
        self.assertEqual(1, result.returncode)
        self.assertIn(f'{app}: assets/index-a1.js uses .toSorted, from Chrome 110\n', result.stderr)
        self.assertIn('3 problems: the TV runs Chromium 108 from file://.', result.stderr)

    def test_a_folder_that_is_not_there_says_to_build_first(self):
        result = self.run_script(pathlib.Path(tempfile.gettempdir()) / 'no-such-dist-tizen')
        self.assertEqual(1, result.returncode)
        self.assertIn('run pnpm build:tizen in web/ first', result.stderr)


if __name__ == '__main__':
    unittest.main()

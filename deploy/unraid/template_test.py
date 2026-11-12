"""Checks the Unraid template and repository profile in deploy/unraid.

python3 -I -m unittest discover -s deploy/unraid -p '*_test.py'

The template must parse, use only the elements and Config attributes
Unraid's Docker page and Community Applications read (PLAN-UNRAID.md §2.3),
and match the image: its paths, port and variables are the ones
deploy/Dockerfile and deploy/entrypoint.sh understand.
"""

import datetime
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

HERE = pathlib.Path(__file__).resolve().parent
TEMPLATE = HERE / "watchmymedia.xml"
PROFILE = HERE / "ca_profile.xml"
DOCKERFILE = HERE.parent / "Dockerfile"

# Elements Unraid's webgui or the Community Applications starter template
# use. Anything else is a typo CA would ignore or flag.
ELEMENTS = {
    "Name", "Repository", "Registry", "Network", "MyIP", "Shell", "Privileged", "Support", "Project",
    "GitHub", "ReadMe", "Overview", "Description", "Category", "WebUI", "TemplateURL", "Icon",
    "Icon-black", "Icon-white", "Icon-azure", "Icon-gray", "ExtraParams", "PostArgs", "ExtraSearchTerms",
    "Requires", "Beta", "MinVer", "MaxVer", "Deprecated", "DeprecatedMaxVer", "Date", "Changes",
    "DefaultTagDescription", "Branch", "Screenshot", "Video", "Photo", "License", "Licence",
    "Discord", "Reddit", "Facebook", "Twitter", "DonateText", "DonateLink", "DonateImg", "Config",
}
CONFIG_ATTRIBUTES = {"Name", "Target", "Default", "Mode", "Description", "Type", "Display", "Required", "Mask"}
TYPES = {"Path", "Port", "Variable", "Label", "Device"}
DISPLAYS = {"always", "advanced", "always-hide", "advanced-hide"}
PATH_MODES = {"rw", "rw,slave", "rw,shared", "ro", "ro,slave", "ro,shared"}


class TemplateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = ET.parse(TEMPLATE).getroot()
        cls.configs = cls.root.findall("Config")
        cls.dockerfile = DOCKERFILE.read_text()

    def text(self, name):
        node = self.root.find(name)
        return (node.text or "").strip() if node is not None else None

    def config(self, name):
        for c in self.configs:
            if c.get("Name") == name:
                return c
        self.fail(f"no Config named {name!r}")

    def test_root_and_elements(self):
        self.assertEqual(self.root.tag, "Container")
        self.assertEqual(self.root.get("version"), "2")
        unknown = {c.tag for c in self.root} - ELEMENTS
        self.assertEqual(unknown, set(), "elements Unraid does not read")
        for required in ("Name", "Repository", "Overview", "Category", "WebUI", "Icon", "TemplateURL", "Date"):
            self.assertTrue(self.text(required), f"{required} is empty")
        self.assertTrue(self.text("Support") or self.text("Project"), "Support or Project is required")
        self.assertIsNone(self.root.find("MaxVer"), "a MaxVer hides the app on the next Unraid release")
        self.assertIsNone(self.root.find("ExtraParams"), "--runtime=nvidia in ExtraParams fails on hosts without the plugin")

    def test_image_and_links(self):
        self.assertEqual(self.text("Repository"), "ghcr.io/watchmymedia/watchmymedia:latest")
        self.assertTrue(self.text("TemplateURL").endswith("/templates/watchmymedia.xml"))
        self.assertTrue(self.text("Icon").endswith("/images/watchmymedia.png"))
        for name in ("Support", "Project", "ReadMe", "Registry", "TemplateURL", "Icon"):
            self.assertRegex(self.text(name), r"^https://", name)
        self.assertEqual(self.text("WebUI"), "http://[IP]:[PORT:27587]")
        self.assertEqual(self.text("Network"), "host")
        self.assertEqual(self.text("Privileged"), "false")
        self.assertEqual(self.text("MinVer"), "6.12.0")

    def test_text_is_plain(self):
        # Angle brackets in descriptions are stripped by Unraid and treated
        # as a security violation by Community Applications.
        for name in ("Overview", "Requires", "Changes"):
            self.assertNotRegex(self.text(name), r"[<>]", name)
        for c in self.configs:
            self.assertNotRegex(c.get("Description", ""), r"[<>]", c.get("Name"))
        self.assertLessEqual(len(self.text("Overview")), 600)

    def test_date_and_changes(self):
        date = datetime.date.fromisoformat(self.text("Date"))
        self.assertLessEqual(date, datetime.date.today())
        self.assertIn(date.isoformat(), self.text("Changes"))

    def test_config_attributes(self):
        names = [c.get("Name") for c in self.configs]
        self.assertEqual(len(names), len(set(names)), "duplicate Config names")
        for c in self.configs:
            with self.subTest(c.get("Name")):
                self.assertEqual(set(c.attrib) - CONFIG_ATTRIBUTES, set())
                self.assertIn(c.get("Type"), TYPES)
                self.assertIn(c.get("Display"), DISPLAYS)
                self.assertIn(c.get("Required"), {"true", "false"})
                self.assertIn(c.get("Mask"), {"true", "false"})
                if c.get("Type") == "Path":
                    self.assertIn(c.get("Mode"), PATH_MODES)
                    self.assertTrue(c.get("Target", "").startswith("/"))
                    self.assertTrue(c.get("Default", "").startswith("/mnt/"))
                elif c.get("Type") == "Port":
                    self.assertIn(c.get("Mode"), {"tcp", "udp"})
                    self.assertTrue(c.get("Target", "").isdigit())
                elif c.get("Type") == "Variable":
                    self.assertRegex(c.get("Target", ""), r"^[A-Z][A-Z0-9_]*$")
                elif c.get("Type") == "Device":
                    # Unraid passes --device=<value> whatever Target says.
                    self.assertTrue(c.get("Default", "").startswith("/dev/"))
                    self.assertEqual(c.get("Required"), "false", "a Device must be removable")

    def test_matches_the_image(self):
        self.assertEqual(self.config("WebUI port").get("Target"), "27587")
        self.assertIn("EXPOSE 27587", self.dockerfile)
        for name, target, env in (("Appdata", "/config", "WATCHMYMEDIA_DATA_DIR=/config"),
                                  ("Transcode", "/transcode", "WATCHMYMEDIA_TRANSCODE_DIR=/transcode"),
                                  ("Movies", "/movies", "WATCHMYMEDIA_MOVIES_PATH=/movies"),
                                  ("TV Shows", "/tv", "WATCHMYMEDIA_TV_PATH=/tv")):
            self.assertEqual(self.config(name).get("Target"), target)
            self.assertIn(env, self.dockerfile, f"{name}: the image no longer uses {target}")
        for name, mode in (("Movies", "ro"), ("TV Shows", "ro"), ("Recordings", "rw"), ("Appdata", "rw"), ("Transcode", "rw")):
            self.assertEqual(self.config(name).get("Mode"), mode, name)
        self.assertEqual(self.config("PUID").get("Default"), "99")
        self.assertEqual(self.config("PGID").get("Default"), "100")
        self.assertEqual(self.config("UMASK").get("Default"), "022")
        entrypoint = (HERE.parent / "entrypoint.sh").read_text()
        for var in ("PUID", "PGID", "UMASK"):
            self.assertIn(f'${{{var}:-', entrypoint, f"the entrypoint no longer reads {var}")
        self.assertEqual(self.config("Intel or AMD GPU").get("Default"), "/dev/dri")
        self.assertIn("/dev/dri/renderD*", entrypoint, "the entrypoint no longer handles /dev/dri groups")
        self.assertEqual(self.config("NVIDIA_VISIBLE_DEVICES").get("Default"), "")
        self.assertEqual(self.config("NVIDIA_DRIVER_CAPABILITIES").get("Default"), "all")
        self.assertNotIn("TZ", [c.get("Target") for c in self.configs], "Unraid sets TZ itself")

    def test_requires_names_every_manual_step(self):
        requires = self.text("Requires")
        for phrase in ("/dev/dri", "Nvidia Driver plugin", "--runtime=nvidia", "NVIDIA_VISIBLE_DEVICES", "WebUI"):
            self.assertIn(phrase, requires)


class ProfileTest(unittest.TestCase):
    def test_profile(self):
        root = ET.parse(PROFILE).getroot()
        self.assertEqual(root.tag, "CommunityApplications")
        profile = (root.findtext("Profile") or "").strip()
        self.assertTrue(profile and not re.search(r"[<>]", profile))
        self.assertTrue((root.findtext("Icon") or "").endswith("/images/watchmymedia.png"))
        self.assertEqual((root.findtext("WebPage") or "").strip(), "https://watchmy.media")


if __name__ == "__main__":
    unittest.main()

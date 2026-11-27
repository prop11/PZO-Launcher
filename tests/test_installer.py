import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import call, patch
from zipfile import ZipFile

import pzo_install_core as core
import pzo_launcher_config as launcher


class CollectorTests(unittest.TestCase):
    def test_json_removes_selectors_from_every_launcher_list(self):
        data = {
            "mainClass": launcher.STOCK_ENTRY,
            "classpath": ["projectzomboid.jar", "mods/example.jar"],
            "vmArgs": [
                '-XX:+UseZGC -XX:-UseG1GC -Dpath="C:\\My Saves\\-XX:+UseZGC"',
                "-XX:+UseEpsilonGC", "-XX:ZUncommitDelay=2", "-XX:+UseGCOverheadLimit",
                "-Dextra=C:\\Extra Files -XX:+UseSerialGC -Dlabel=Two Words",
            ],
            "windows": {
                "6.1": {"vmArgs": ["-XX:+UseSerialGC"]},
                "10.0.17134": {"vmArgs": ["-XX:+UseParallelGC -Dplatform=windows"], "custom": True},
            },
            "linux": {"vmArgs": ["-XX:+UseShenandoahGC", "-XX:ShenandoahGCMode=generational"]},
            "osx": {"custom": [{"vmArgs": ["-XX:-UseConcMarkSweepGC", "-Dplatform=mac"]}]},
        }
        self.patch_json(data)
        args = data["vmArgs"]
        self.assertIn('-Dpath="C:\\My Saves\\-XX:+UseZGC"', args)
        self.assertIn("-Dextra=C:\\Extra Files", args)
        self.assertIn("-Dlabel=Two Words", args)
        self.assertIn("-XX:+UseGCOverheadLimit", args)
        self.assertEqual(data["windows"], {
            "10.0.17134": {"vmArgs": ["-Dplatform=windows"], "custom": True},
        })
        self.assertNotIn("linux", data)
        self.assertEqual(data["osx"]["custom"][0]["vmArgs"], ["-Dplatform=mac"])
        self.assertIn("mods/example.jar", data["classpath"])
        first = json.dumps(data, sort_keys=True)
        self.patch_json(data)
        self.assertEqual(first, json.dumps(data, sort_keys=True))

    @staticmethod
    def patch_json(data):
        launcher.patch_json(data, "Proton", launcher.G1, "-Xms1024m", "-Xmx4096m",
                            25, False, False, False, False)
        launcher.validate_gc(data, {"vmArgs"}, (data, "vmArgs"), launcher.G1[0])

    def test_mac_places_collector_and_agent_in_one_option_list(self):
        jar = Path("Project Zomboid.app") / "Contents/Java/PZOptimEngine.jar"
        for options_at_root in (False, True):
            with self.subTest(options_at_root=options_at_root):
                data = {"Java": {"VMOptions": ["-XX:+UseParallelGC", "-Dlegacy=true"]},
                        "JVMMainClassName": "com.pzoptimizer.PZOEntrypoint"}
                if options_at_root:
                    data["JVMOptions"] = ["-XX:+UseZGC", "-XX:+ZGenerational", "-Dexisting=true"]
                launcher.patch_plist(data, {"JVMMainClassName": "game/Stock"}, launcher.G1,
                                     4096, 17, jar)
                roundtrip = launcher.read_config(Path("Info.plist"), launcher.encode(Path("Info.plist"), data))
                primary = launcher.plist_primary(roundtrip)
                launcher.validate_gc(roundtrip, {"JVMOptions", "VMOptions"}, primary, launcher.G1[0])
                self.assertEqual(roundtrip["JVMMainClassName"], "game/Stock")
                self.assertIn("-Dlegacy=true", roundtrip["Java"]["VMOptions"])
                all_args = [a for holder, key, _ in launcher.option_lists(roundtrip, {"JVMOptions", "VMOptions"})
                            for a in holder[key]]
                self.assertEqual(sum(a.startswith("-javaagent:") for a in all_args), 1)

    def test_preserves_custom_windows_heap_and_bounds_initial_heap(self):
        self.assertEqual(launcher.windows_heap(["-Xms8g", "-Xmx6g"], "-Xmx8192m", False),
                         ("-Xms6g", "-Xmx6g"))
        self.assertEqual(launcher.windows_heap(["-Xms4096m", "-Xmx8192m", "-XX:+AlwaysPreTouch"],
                                               "-Xmx8192m", False),
                         ("-Xms1024m", "-Xmx8192m"))


class ReleaseTests(unittest.TestCase):
    @staticmethod
    def release(tag, date, *, has_jar=True, draft=False, prerelease=False):
        asset = {"name": launcher.ENGINE, "state": "uploaded", "size": 3, "digest": None}
        return {"tag_name": tag, "published_at": date, "draft": draft,
                "prerelease": prerelease, "assets": [asset] if has_jar else []}

    def test_catalog_skips_drafts_and_no_asset_releases_and_fetches_more_pages(self):
        first = [self.release("V6", "2026-11-06"), self.release("V5", "2026-11-05"),
                 self.release("draft", "2026-11-08", draft=True)]
        first.extend(self.release(f"missing{i}", "2026-11-07", has_jar=False) for i in range(27))
        second = [self.release("V3", "2026-11-03", prerelease=True), self.release("V4", "2026-11-04"),
                  self.release("V2", "2026-11-02"), self.release("V1", "2026-11-01")]
        with patch.object(core, "release_json", side_effect=[first, second]) as request:
            versions = core.recent_releases()
        self.assertEqual(request.call_args_list, [call("?per_page=30&page=1"), call("?per_page=30&page=2")])
        self.assertEqual([release.label for release in versions], ["V6", "V5", "V4", "V3 (prerelease)", "V2"])

    def test_latest_uses_github_latest_endpoint(self):
        with patch.object(core, "release_json", return_value=self.release("V5", "2026-11-05")) as request:
            self.assertEqual(core.latest_release().tag, "V5")
        request.assert_called_once_with("/latest")

    def test_exact_download_verifies_size_and_digest(self):
        digest = "sha256:" + hashlib.sha256(b"foo").hexdigest()
        release = core.EngineRelease("test/1", "2026-11-25", False, 3, digest)
        with patch.object(core, "fetch", return_value=b"foo") as request:
            self.assertEqual(core.download_engine(release), b"foo")
        request.assert_called_once_with(f"{core.RELEASES}/download/test%2F1/{launcher.ENGINE}", limit=3)
        with patch.object(core, "fetch", return_value=b"fo"):
            with self.assertRaisesRegex(ValueError, "incomplete"):
                core.download_engine(release)
        with patch.object(core, "fetch", return_value=b"bar"):
            with self.assertRaisesRegex(ValueError, "checksum"):
                core.download_engine(release)


class TransactionTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.game_root = self.root / "Game"
        self.game_root.mkdir()
        self.config = self.game_root / core.JSON_FILE
        self.stock = b'{"mainClass":"zombie/gameStates/MainScreenState","vmArgs":["-XX:+UseG1GC"]}'
        self.config.write_bytes(self.stock)
        self.game = core.Game(self.game_root, "Proton", (self.config,), (self.game_root,),
                              None, (self.root / "Zomboid",))
        self.engine = self.root / launcher.ENGINE
        self.write_engine("3.0-test")
        for fn, value in (("game_running", False), ("steam_zombie_agent", False), ("ram_gib", 12)):
            mock = patch.object(core, fn, return_value=value)
            mock.start()
            self.addCleanup(mock.stop)
        self.latest = core.EngineRelease("V3.0", "2026-11-25", False, 1, "")
        for fn, kwargs in (("latest_release", {"return_value": self.latest}),
                           ("download_engine", {"side_effect": lambda release: self.engine.read_bytes()})):
            mock = patch.object(core, fn, **kwargs)
            setattr(self, fn, mock.start())
            self.addCleanup(mock.stop)

    def write_engine(self, version):
        with ZipFile(self.engine, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", f"Implementation-Version: {version}\n")
            jar.writestr("com/pzoptimizer/PZOEntrypoint.class", b"")

    def test_install_update_repair_repeat_uninstall(self):
        plan = core.make_plan(self.game, "install", "standard")
        self.assertEqual(self.config.read_bytes(), self.stock)
        backup = core.apply(plan)
        self.assertTrue((backup / "files.json").is_file())
        self.assertTrue(self.game.engine.is_file())
        self.write_engine("3.1-test")
        core.apply(core.make_plan(self.game, "update", "standard"))
        self.assertEqual(core.installed_version(self.game.engine), "3.1-test")
        self.assertEqual(self.latest_release.call_count, 2)
        self.assertEqual(self.download_engine.call_args_list, [call(self.latest), call(self.latest)])

        broken = json.loads(self.config.read_bytes())
        broken["windows"] = {"10.0": {"vmArgs": ["-XX:+UseZGC -XX:+UseEpsilonGC", "-Dcustom=true"]}}
        self.config.write_text(json.dumps(broken))
        core.apply(core.make_plan(self.game, "repair", "standard"))
        repaired = json.loads(self.config.read_bytes())
        launcher.validate_gc(repaired, {"vmArgs"}, (repaired, "vmArgs"), launcher.G1[0])
        self.assertEqual(repaired["windows"]["10.0"]["vmArgs"], ["-Dcustom=true"])
        self.assertFalse(core.make_plan(self.game, "repair", "standard").files)
        core.apply(core.make_plan(self.game, "uninstall", "standard"))
        self.assertEqual(self.config.read_bytes(), self.stock)
        self.assertFalse(self.game.engine.exists())
        self.assertEqual(self.download_engine.call_count, 2)

    def test_selected_release_drives_install_review(self):
        older = core.EngineRelease("V2.9", "2026-11-22", False, 1, "")
        with patch.object(core, "recent_releases", return_value=[self.latest, older]):
            plan = core.make_plan(self.game, "install", "standard", release="v2.9")
        self.latest_release.assert_not_called()
        self.download_engine.assert_called_once_with(older)
        self.assertIn("Engine package from GitHub release V2.9.", plan.notes)
        self.assertFalse(self.game.engine.exists())

    def test_download_failure_leaves_existing_launch_files_unchanged(self):
        self.download_engine.side_effect = ValueError("GitHub unavailable")
        with self.assertRaisesRegex(ValueError, "GitHub unavailable"):
            core.make_plan(self.game, "install", "standard")
        self.assertFalse(self.game.engine.exists())
        self.assertEqual(self.config.read_bytes(), self.stock)

    def test_custom_jvm_settings_are_applied_and_retained(self):
        settings = core.preferences_path(self.game.user_data[0])
        settings.parent.mkdir(parents=True)
        settings.write_text('{"compat_mode":"on","beta_opt_in":true}', encoding="ascii")
        plan = core.make_plan(self.game, "install", "standard", collector="serial", max_heap="3")
        self.assertEqual(plan.gc, "Serial GC")
        self.assertEqual(plan.heap, "3 GiB")
        self.assertEqual(json.loads(plan.files[settings]),
                         {"compat_mode": "on", "beta_opt_in": True, "preserve_gc": True})
        core.apply(plan)
        data = launcher.read_config(self.config)
        launcher.validate_gc(data, {"vmArgs"}, (data, "vmArgs"), "-XX:+UseSerialGC")
        self.assertIn("-Xmx3g", data["vmArgs"])
        self.assertFalse(core.make_plan(self.game, "repair", "standard").files)

    def test_unavailable_collector_and_invalid_heap_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "unavailable"):
            core.make_plan(self.game, "install", "standard", collector="shenandoah")
        self.assertEqual(core.heap_mib("4.1", 12), 4198)
        for size in ("NaN", "13", "0", "junk"):
            with self.subTest(size=size):
                with self.assertRaisesRegex(ValueError, "maximum heap"):
                    core.make_plan(self.game, "install", "standard", max_heap=size)

    def test_failed_apply_rolls_back_and_keeps_backups(self):
        plan = core.make_plan(self.game, "install", "standard")
        original_write = core.write_atomic

        def failing_write(path, data, mode=None):
            if path == self.config:
                raise OSError("Simulated launcher write error")
            original_write(path, data, mode)

        with patch.object(core, "write_atomic", side_effect=failing_write):
            with self.assertRaisesRegex(OSError, "Simulated launcher write error"):
                core.apply(plan)
        self.assertFalse(self.game.engine.exists())
        self.assertEqual(self.config.read_bytes(), self.stock)
        self.assertFalse((self.game.backup / ".installer.lock").exists())
        self.assertTrue(any(self.game.backup.glob("PZO_*/files.json")))

    def test_apply_refuses_a_launcher_that_changed_after_review(self):
        plan = core.make_plan(self.game, "install", "standard")
        self.config.write_bytes(self.stock + b"\n")
        with self.assertRaisesRegex(ValueError, "changed after review"):
            core.apply(plan)
        self.assertFalse(self.game.engine.exists())


if __name__ == "__main__":
    unittest.main()

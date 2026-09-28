from typer.testing import CliRunner

from qod_cli import __version__
from qod_cli.main import app

runner = CliRunner()


def test_version_flag():
    result = runner.invoke(app, ["--version"])
    assert result.exit_code == 0
    assert __version__ in result.output


def test_every_command_names_the_profile_file_on_stderr(isolated_env):
    result = runner.invoke(app, ["--profile", "prod", "config", "profiles"])
    path = isolated_env / "config.toml"
    assert f"qod: profile 'prod' from {path} (not found)" in result.stderr
    assert "qod: profile" not in result.stdout

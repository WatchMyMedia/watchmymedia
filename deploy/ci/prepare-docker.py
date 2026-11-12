"""Give a CI job its own Docker config without inherited registry credentials."""
import json
import os
import pathlib
import sys


def prepare(source, destination):
    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
    config_path = source / 'config.json'
    config = json.loads(config_path.read_text()) if config_path.exists() else {}
    for key in ('auths', 'credsStore', 'credHelpers'):
        config.pop(key, None)
    # A nonempty auth map prevents Docker from auto-selecting osxkeychain.
    # This empty Docker Hub entry requests anonymous pulls, not a login.
    config['auths'] = {'https://index.docker.io/v1/': {}}
    plugins = source / 'cli-plugins'
    if plugins.exists():
        config.setdefault('cliPluginsExtraDirs', []).append(str(plugins.resolve()))
    # Context metadata selects OrbStack/Desktop's socket. Buildx's config and
    # installed plugins still live in the job's own directory, not this one.
    contexts = source / 'contexts'
    if contexts.exists():
        (destination / 'contexts').symlink_to(contexts.resolve(), target_is_directory=True)
    with os.fdopen(os.open(destination / 'config.json',
                           os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as out:
        json.dump(config, out)


if __name__ == '__main__':
    prepare(*(pathlib.Path(arg) for arg in sys.argv[1:]))

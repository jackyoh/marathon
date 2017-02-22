""" Test using enterprise marathon on marathon (MoM-EE). The individual steps
    to install MoM-EE are well documented here: 
    https://wiki.mesosphere.com/display/DCOS/MoM+1.4 
"""

import os
import shutil
import subprocess
import pytest

from common import *
from shakedown import *

MOM_EE_NAME = 'marathon-user-ee'
MOM_EE_SERVICE_ACCOUNT = 'marathon_user_ee'
MOM_EE_SECRET_NAME = 'my-secret'

PRIVATE_KEY_FILE = 'private-key.pem'
PUBLIC_KEY_FILE = 'public-key.pem'

MOM_IMAGES = {
    '1.4': '1.4.1_1.9.7',
    '1.3': '1.3.10_1.1.5'
}

def is_mom_ee_deployed():
    mom_ee_id = '/{}'.format(MOM_EE_NAME)
    client = marathon.create_client()
    apps = client.get_apps()
    return any(app['id'] == mom_ee_id for app in apps)


def remove_mom_ee():
    print('Removing {}...'.format(MOM_EE_NAME))
    if service_available_predicate(MOM_EE_NAME):
        with marathon_on_marathon(name=MOM_EE_NAME):
            delete_all_apps()

    client = marathon.create_client()
    client.remove_app(MOM_EE_NAME)
    deployment_wait()
    print('Successfully removed {}'.format(MOM_EE_NAME))


def assert_mom_ee(version, security_mode='permissive'):
    ensure_prerequisites_installed()
    ensure_service_account()
    ensure_permissions()
    ensure_secret(strict=True if security_mode == 'strict' else False)
    ensure_docker_credentials()

    # Deploy MoM-EE in permissive mode
    app_def_file = '{}/mom-ee-{}-{}.json'.format(fixture_dir(), security_mode, version)
    assert os.path.isfile(app_def_file), "Couldn't find appropriate MoM-EE definition: {}".format(app_def_file)

    print('Deploying {} definition with {} image'.format(app_def_file, MOM_IMAGES[version]))

    app_def = get_resource(app_def_file)
    app_def['container']['docker']['image'] = 'mesosphere/marathon-dcos-ee:{}'.format(MOM_IMAGES[version])

    client = marathon.create_client()
    client.add_app(app_def)
    deployment_wait()
    wait_for_service_endpoint(MOM_EE_NAME)

@strict
@dcos_1_9
def test_mom_ee_strict_1_4():
    assert_mom_ee('1.4', 'strict')
    assert simple_sleep_app()

@dcos_1_9
def test_mom_ee_permissive_1_4():
    assert_mom_ee('1.4', 'permissive')
    assert simple_sleep_app()

@dcos_1_9
def test_mom_ee_disabled_1_4():
    assert_mom_ee('1.4', 'disabled')
    assert simple_sleep_app()

@strict
@dcos_1_9
def test_mom_ee_strict_1_3():
    assert_mom_ee('1.3', 'strict')
    assert simple_sleep_app()

@dcos_1_9
def test_mom_ee_permissive_1_3():
    assert_mom_ee('1.3', 'permissive')
    assert simple_sleep_app()

@dcos_1_9
def test_mom_ee_disabled_1_3():
    assert_mom_ee('1.3', 'disabled')
    assert simple_sleep_app()


def simple_sleep_app():
    # Deploy a simple sleep app in the MoM-EE
    with marathon_on_marathon(name=MOM_EE_NAME):
        client = marathon.create_client()
        
        app_id = uuid.uuid4().hex
        app_def = app(app_id)
        client.add_app(app_def)
        deployment_wait()

        tasks = get_service_task(MOM_EE_NAME, app_id)
        print('MoM-EE tasks: {}'.format(tasks))
        return tasks is not None


def install_prerequisites():
    print('Installing dcos-enterprise-cli package')
    stdout, stderr, return_code = run_dcos_command('package install dcos-enterprise-cli')
    assert return_code == 0, "Failed to install dcos-enterprise-cli package"


def are_prerequisites_installed():
    stdout, stderr, return_code = run_dcos_command('package list --json')
    result_json = json.loads(stdout)
    return any(cmd['name'] == 'dcos-enterprise-cli' for cmd in result_json)


def ensure_prerequisites_installed():
    if not are_prerequisites_installed():
        install_prerequisites()
    assert are_prerequisites_installed() == True


def delete_service_account():
    print('Removing existing service account {}'.format(MOM_EE_SECRET_NAME))
    stdout, stderr, return_code = run_dcos_command('security org service-accounts delete {}'.format(MOM_EE_SERVICE_ACCOUNT))
    assert return_code == 0, "Failed to create a service account"


def add_service_account():
    # Remove existing (if any) private and public key files to create a new pair
    if os.path.isfile(PRIVATE_KEY_FILE):
        os.remove(PRIVATE_KEY_FILE)
    if os.path.isfile(PUBLIC_KEY_FILE):
        os.remove(PUBLIC_KEY_FILE)

    # Create new private and public keys
    print('Creating a key pair for the service account')
    stdout, stderr, return_code = run_dcos_command('security org service-accounts keypair {} {}'.format(PRIVATE_KEY_FILE, PUBLIC_KEY_FILE))
    assert os.path.isfile(PRIVATE_KEY_FILE), "Private key of the service account key pair not found"
    assert os.path.isfile(PUBLIC_KEY_FILE), "Public key of the service account key pair not found"

    print('Creating {} service account'.format(MOM_EE_SERVICE_ACCOUNT))
    stdout, stderr, return_code = run_dcos_command('security org service-accounts create -p {} -d "Marathon-EE service account" {}'.format(
        PUBLIC_KEY_FILE, 
        MOM_EE_SERVICE_ACCOUNT))

    os.remove(PUBLIC_KEY_FILE)

    assert return_code == 0


def has_service_account():
    stdout, stderr, return_code = run_dcos_command('security org service-accounts show --json')
    result_json = json.loads(stdout)
    return MOM_EE_SERVICE_ACCOUNT in result_json


def ensure_service_account():
    if has_service_account():
        delete_service_account()
    add_service_account()
    assert has_service_account()


def grant_permissions():
    cmd = [
        shutil.which('curl'),
        '-X', 'PUT',
        '-k',
        '-H', 'Authorization: token={}'.format(dcos_acs_token()),
        '{}acs/api/v1/acls/dcos:superuser/users/{}/full'.format(dcos_url(), MOM_EE_SERVICE_ACCOUNT)
    ]
    
    print('Granting full permissions to {}'.format(MOM_EE_SERVICE_ACCOUNT))
    stdout, stderr, return_code = run(cmd)
    assert return_code == 0, "Failed to grant permissions to the service account"


def ensure_permissions():
    grant_permissions()

    cmd = [
        shutil.which('curl'),
        '-X', 'GET',
        '-k',
        '-H', 'Authorization: token={}'.format(dcos_acs_token()),
        '{}acs/api/v1/acls/dcos:superuser/users/{}'.format(dcos_url(), MOM_EE_SERVICE_ACCOUNT)
    ]
    
    stdout, stderr, return_code = run(cmd)
    result_json = json.loads(stdout)

    assert result_json['array'][0]['url'] == '/acs/api/v1/acls/dcos:superuser/users/{}/full'.format(MOM_EE_SERVICE_ACCOUNT), "Service account permissions couldn't be set"


def has_secret():
    stdout, stderr, return_code = run_dcos_command('security secrets list / --json')
    if stdout:
        result_json = json.loads(stdout)
        return MOM_EE_SECRET_NAME in result_json
    return False


def delete_secret():
    print('Removing existing secret {}'.format(MOM_EE_SECRET_NAME))
    stdout, stderr, return_code = run_dcos_command('security secrets delete {}'.format(MOM_EE_SECRET_NAME))
    assert return_code == 0, "Failed to remove existing secret"


def create_secret(strict=False):
    # We assume that previously created private key is still there. Creating secrets only
    # makes sense in connection with service account since it's the same key pair
    assert os.path.isfile(PRIVATE_KEY_FILE), "Failed to create secret: public key not found"

    print('Creating new secret {}'.format(MOM_EE_SECRET_NAME))
    strict_opt = '--strict' if strict else ''
    stdout, stderr, return_code = run_dcos_command('security secrets create-sa-secret {} {} {} {}'.format(
        strict_opt,
        PRIVATE_KEY_FILE, 
        MOM_EE_SERVICE_ACCOUNT, 
        MOM_EE_SECRET_NAME))

    os.remove(PRIVATE_KEY_FILE)
    assert return_code == 0, "Failed to create a secret"

def ensure_secret(strict=False):
    if has_secret():
        delete_secret()
    create_secret(strict)
    assert has_secret()


def copy_docker_credentials():
    DOCKER_TAR_GZ = 'docker.tar.gz'
    create_docker_credentials_file(DOCKER_TAR_GZ)

    # Upload docker.tar.gz to all private agents
    try:
        print('Uploading tarball with docker credentials to all private agents...')
        agents = get_private_agents()
        for agent in agents:
            print("Copying docker credentials to {}".format(agent))
            copy_file_to_agent(agent, DOCKER_TAR_GZ)
    except Exception as e:
        print('Failed to upload {} to agent: {}'.format(DOCKER_TAR_GZ, agent))
        raise e
    finally:
        os.remove(DOCKER_TAR_GZ)


def ensure_docker_credentials():
    copy_docker_credentials()


def setup_function(function):
    if is_mom_ee_deployed():
        remove_mom_ee()
    assert not is_mom_ee_deployed()


def teardown_module(module):
    remove_mom_ee()
    delete_service_account()
    delete_secret()


def run(command):
    """ Run `{command}` locally

        :param command: the command to execute
        :type command: str

        :return: (stdout, stderr, return_code)
        :rtype: tuple
    """
    print('Running: {}'.format(' '.join(command)))

    proc = subprocess.Popen(command, stdout = subprocess.PIPE, stderr=subprocess.PIPE)
    output, error = proc.communicate()
    return_code = proc.wait()
    stdout = output.decode('utf-8')
    stderr = error.decode('utf-8')

    print(stdout, stderr, return_code)

    return stdout, stderr, return_code

"""
CPAchecker is a tool for configurable software verification.
This file is part of CPAchecker.

Copyright (C) 2007-2015  Dirk Beyer
All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.


CPAchecker web page:
  http://cpachecker.sosy-lab.org
"""

# prepare for Python 3
from __future__ import absolute_import, division, print_function, unicode_literals

import sys
sys.dont_write_bytecode = True # prevent creation of .pyc files

import logging
import os
import shutil
import threading

import urllib.request as urllib2
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import as_completed

from .webclient import *  # @UnusedWildImport

"""
This module provides a BenchExec integration for the web interface of the VerifierCloud.
"""

_webclient = None
_print_lock = threading.Lock()

def init(config, benchmark):
    global _webclient

    if not benchmark.config.cpu_model:
        logging.warning("It is strongly recommended to set a CPU model('--cloudCPUModel'). "\
                        "Otherwise the used machines and CPU models are undefined.")

    if not config.cloudMaster:
        logging.warning("No URL of a VerifierCloud instance is given.")
        return

    if config.revision:
        tokens = config.revision.split(':')
        svn_branch = tokens[0]
        if len(tokens) > 1:
            svn_revision = config.revision.split(':')[1]
        else:
            svn_revision = 'HEAD'
    else:
        svn_branch = 'trunk'
        svn_revision = 'HEAD'

    _webclient = WebInterface(config.cloudMaster, config.cloudUser, svn_branch, svn_revision)

    benchmark.tool_version = _webclient.tool_revision()
    logging.info('Using CPAchecker version {0}.'.format(benchmark.tool_version))
    benchmark.executable = 'scripts/cpa.sh'

def get_system_info():
    return None

def execute_benchmark(benchmark, output_handler):
    global _webclient

    if (benchmark.tool_name != 'CPAchecker'):
        logging.warning("The web client does only support the CPAchecker.")
        return

    if not _webclient:
        logging.warning("No valid URL of a VerifierCloud instance is given.")
        return

    STOPPED_BY_INTERRUPT = False
    try:
        for runSet in benchmark.run_sets:
            if not runSet.should_be_executed():
                output_handler.output_for_skipping_run_set(runSet)
                continue

            output_handler.output_before_run_set(runSet)
            result_futures = _submitRunsParallel(runSet, benchmark)

            _handle_results(result_futures, output_handler, benchmark)
            output_handler.output_after_run_set(runSet)

    except KeyboardInterrupt as e:
        STOPPED_BY_INTERRUPT = True
        raise e
    except:
        stop()
        raise
    finally:
        output_handler.output_after_benchmark(STOPPED_BY_INTERRUPT)

    stop()

def stop():
    global _webclient
    _webclient.shutdown()
    _webclient = None

def _submitRunsParallel(runSet, benchmark):
    global _webclient

    logging.info('Submitting runs...')

    executor = ThreadPoolExecutor(MAX_SUBMISSION_THREADS)
    submission_futures = {}
    submissonCounter = 1
    limits = benchmark.rlimits
    cpu_model = benchmark.config.cpu_model
    result_files_pattern = benchmark.result_files_pattern
    priority = benchmark.config.cloudPriority

    for run in runSet.runs:
        submisson_future = executor.submit(_webclient.submit, run, limits, cpu_model, result_files_pattern, priority)
        submission_futures[submisson_future] = run

    executor.shutdown(wait=False)

    #collect results futures
    result_futures = {}
    try:
        for future in as_completed(submission_futures.keys()):
            try:
                run = submission_futures[future]
                result_futures[future.result()] = run

                if submissonCounter % 50 == 0:
                    logging.info('Submitted run {0}/{1}'.\
                                format(submissonCounter, len(runSet.runs)))


            except (urllib2.HTTPError, WebClientError) as e:
                try:
                    message = e.read() #not all HTTPErrors have a read() method
                except AttributeError:
                    message = ""
                logging.warning('Could not submit run {0}: {1}. {2}'.\
                    format(run.identifier, e, message))
            finally:
                submissonCounter += 1
    finally:
        for future in submission_futures.keys():
            future.cancel() # for example in case of interrupt

    _webclient.flush_runs()
    logging.info("Run submission finished.")
    return result_futures

def _handle_results(result_futures, output_handler, benchmark):
    executor = ThreadPoolExecutor(MAX_SUBMISSION_THREADS)

    for result_future in as_completed(result_futures.keys()):
        run = result_futures[result_future]
        result = result_future.result()
        executor.submit(_unzip_and_handle_result, result, run, output_handler, benchmark)

def _unzip_and_handle_result(zip_content, run, output_handler, benchmark):
    """
    Call handle_result with appropriate parameters to fit into the BenchExec expectations.
    """

    def _open_output_log(output_path):
        log_file = open(run.log_file, 'wb')
        log_header = " ".join(run.cmdline()) + "\n\n\n--------------------------------------------------------------------------------\n"
        log_file.write(log_header.encode('utf-8'))
        return log_file

    def _handle_run_info(values):
        if "memory" in values:
            values["memUsage"] = int(values.pop("memory").strip('B'))

        run.walltime = float(values["walltime"].strip('s'))
        run.cputime = float(values["cputime"].strip('s'))
        return_value = int(values["exitcode"])

        # remove irrelevant columns
        values.pop("command", None)
        values.pop("timeLimit", None)
        values.pop("coreLimit", None)

        # prefix other values with @vcloud- to make them hidden by default
        for key in list(values.keys()):
            if not key in {'cputime', 'walltime', 'memUsage'} \
                    and not key.startswith('energy'):
                values['@vcloud-'+key] = values.pop(key)

        run.values.update(values)
        return return_value

    def _handle_host_info(values):
        host = values.pop("name", "-")
        output_handler.store_system_info(
            values.pop("os", "-"), values.pop("cpuModel", "-"), values.pop("cores", "-"),
            values.pop("frequency", "-"), values.pop("memory", "-"),
            host, runSet=run.runSet)

        # prefix other values with @vcloud- to make them hidden by default
        for key in list(values.keys()):
            values['@vcloud-'+key] = values.pop(key)

        values["host"] = host
        run.values.update(values)

    def _handle_stderr_file(result_zip_file, files, output_path):
        if RESULT_FILE_STDERR in files:
            result_zip_file.extract(RESULT_FILE_STDERR, output_path)
            shutil.move(os.path.join(output_path, RESULT_FILE_STDERR), run.log_file + ".stdError")
            os.rmdir(output_path)

    return_value = handle_result(
        zip_content, run.log_file + ".output", run.identifier, benchmark.result_files_pattern,
        _open_output_log, _handle_run_info, _handle_host_info, _handle_stderr_file)

    if return_value is not None:
        run.after_execution(return_value)

        with _print_lock:
            output_handler.output_before_run(run)
            output_handler.output_after_run(run)

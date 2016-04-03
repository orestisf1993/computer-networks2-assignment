#!/usr/bin/env python
# coding: utf-8

import json
import logging
import os
from datetime import datetime

import matplotlib.dates
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.ticker import FuncFormatter

DATE_FORMAT = matplotlib.dates.DateFormatter('%H:%M:%S')
PLOT_PATH = os.path.join('report', 'plots', datetime.now().strftime(r'%Y-%m-%d'))
ECHO_PACKAGE_LENGTH_BYTES = 37


def read_codes(filename="codes.json"):
    with open(filename) as file_obj:
        logging.debug("Reading codes from file:%s.", filename)
        return json.load(file_obj)


def read_times(filename):
    with open(filename) as file_obj:
        # start_time = int(file_obj.readline())
        times = np.fromiter((tuple(map(int, line.split(":")))[0] for line in file_obj), dtype=int)
    # return start_time, times
    return times


def plt_add_stats(mean, std):
    plt.suptitle(r"$\mu$ =" + " {0:.1f} ms".format(mean) +
                 r", $\sigma$ = " +
                 "{0:.2f} ms".format(std))


def plot_code(code):
    times = read_times(code + ".txt")
    # start_time = times[0]
    diffs = np.diff(times)
    mean = diffs.mean(dtype=int)
    std = diffs.std()
    times_d = [datetime.fromtimestamp(ms_time / 1000) for ms_time in times]
    # diffs_d = np.diff(times_d)

    throughput = {}
    for seconds_limit in (8, 16, 32):
        millis_limit = seconds_limit * 1000
        n_bins = int(np.ceil((times[-1] - times[0]) / millis_limit))
        x = np.zeros(n_bins, dtype=datetime)
        bins = np.zeros(n_bins)
        for limit in range(n_bins):
            start = np.searchsorted(times, times[0] + limit * millis_limit, 'left')
            end = np.searchsorted(times, times[0] + (limit + 1) * millis_limit, 'right')
            divisor = (times[min(end, len(times) - 1)] - times[start]) / 1000
            if divisor == 0:
                continue
            x[limit] = times_d[start]
            bins[limit] = (end - start) * ECHO_PACKAGE_LENGTH_BYTES / divisor
        throughput[seconds_limit] = x[x != 0], bins[bins != 0]

    def plot_throughput(limit, x, averages):
        mean = averages.mean()
        std = averages.std()

        plt.figure()
        plt.plot_date(x, averages)
        ax = plt.gca()
        ax.xaxis.set_major_formatter(DATE_FORMAT)
        ax.yaxis.grid(True)
        plt.gcf().autofmt_xdate()
        plt.title("Ρυθμαπόδοση ανά {limit} seconds ({code})".format(code=code, limit=limit))
        plt_add_stats(mean, std)
        plt.xlabel("Χρόνος Άφιξης")
        plt.ylabel("Ρυθμός (bps)")
        plt.savefig(filename=os.path.join(PLOT_PATH, '{code}-lim{lim}.pdf'.format(code=code, lim=limit)), format='pdf')

    for key, value in throughput.items():
        plot_throughput(key, *value)

    plt.figure()
    x = range(1, len(diffs) + 1)
    plt.scatter(x, diffs)
    ax = plt.gca()
    ax.yaxis.grid(True)
    plt.xlim(x[0] * 0.95, x[-1] * 1.01)
    plt.title("Χρόνος Απόκρισης για κάθε πακέτο ({code})".format(code=code))
    plt.xlabel("Αριθμός Πακέτου")
    plt.ylabel("Χρόνος Απόκρισης")
    plt_add_stats(mean, std)
    plt.savefig(filename=os.path.join(PLOT_PATH, '{code}-response-time.pdf'.format(code=code)), format='pdf')

    def to_percent(y, position):
        # Ignore the passed in position. This has the effect of scaling the default
        # tick locations.
        s = str(100 * y)

        # The percent symbol needs escaping in latex
        if matplotlib.rcParams['text.usetex'] is True:
            return s + r'$\%$'
        else:
            return s + '%'

    def perc_weights(data):
        return (np.zeros_like(data) + 1 / data.size)

    percent_formatter = FuncFormatter(to_percent)

    plt.figure()
    plt.hist(diffs, bins=10, weights=perc_weights(diffs), normed=False)
    plt.title("Συχνότητα ανά χρόνο απόκρισης")
    plt.xlabel("Χρόνος Απόκρισης")
    plt.ylabel("Συχνότητα")
    plt.gca().yaxis.set_major_formatter(percent_formatter)
    plt.savefig(filename=os.path.join(PLOT_PATH, '{code}-hist.pdf'.format(code=code)), format='pdf')

    # Histograms from throughput.
    for key, value in throughput.items():
        averages = value[1]
        plt.figure()
        plt.hist(averages, bins=10, weights=perc_weights(averages), normed=False)
        plt.title("Συχνότητα throughput")
        plt.xlabel("Throughput")
        plt.ylabel("Συχνότητα")
        plt.gca().yaxis.set_major_formatter(percent_formatter)
        plt.savefig(filename=os.path.join(PLOT_PATH, '{code}-lim{lim}-hist.pdf'.format(code=code, lim=key)),
                    format='pdf')


# Init matplotlib & settings.
plt.rc('text', usetex=False)
plt.style.use('ggplot')
matplotlib.rc('font', family='Ubuntu')

codes = read_codes()
plot_code('E0000')
plot_code(codes['echoRequestCode'])
os.makedirs(PLOT_PATH, exist_ok=True)

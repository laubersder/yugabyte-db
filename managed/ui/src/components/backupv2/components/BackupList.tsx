/*
 * Created on Thu Feb 10 2022
 *
 * Copyright 2021 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

import moment from 'moment';
import React, { FC, useMemo, useReducer, useState } from 'react';
import { DropdownButton, MenuItem, Row } from 'react-bootstrap';
import { RemoteObjSpec, SortOrder, TableHeaderColumn } from 'react-bootstrap-table';
import { useQuery } from 'react-query';
import { useSelector } from 'react-redux';
import Select, { OptionTypeBase } from 'react-select';
import { Backup_States, getBackupsList, IBackup, TIME_RANGE_STATE } from '..';
import { StatusBadge } from '../../common/badge/StatusBadge';
import { YBButton, YBMultiSelectRedesiged } from '../../common/forms/fields';
import { YBLoading } from '../../common/indicators';
import { BackupDetails } from './BackupDetails';
import {
  BACKUP_STATUS_OPTIONS,
  CALDENDAR_ICON,
  convertArrayToMap,
  DATE_FORMAT,
  ENTITY_NOT_AVAILABLE,
  FormatUnixTimeStampTimeToTimezone
} from '../common/BackupUtils';
import './BackupList.scss';
import { BackupCancelModal, BackupDeleteModal } from './BackupDeleteModal';
import { BackupRestoreModal } from './BackupRestoreModal';
import { YBSearchInput } from '../../common/forms/fields/YBSearchInput';
import { BackupCreateModal } from './BackupCreateModal';
import { useSearchParam } from 'react-use';
import { AssignBackupStorageConfig } from './AssignBackupStorageConfig';
import { formatBytes } from '../../xcluster/ReplicationUtils';
import { BackupAdvancedRestore } from './BackupAdvancedRestore';
import clsx from 'clsx';
import { AccountLevelBackupEmpty, UniverseLevelBackupEmpty } from './BackupEmpty';
import { YBTable } from '../../common/YBTable';
import { find } from 'lodash';
import { fetchTablesInUniverse } from '../../../actions/xClusterReplication';
import { BackupThrottleParameters } from './BackupThrottleParameters';

const reactWidgets = require('react-widgets');
const momentLocalizer = require('react-widgets-moment');
require('react-widgets/dist/css/react-widgets.css');

const { DateTimePicker } = reactWidgets;
momentLocalizer(moment);

const DEFAULT_SORT_COLUMN = 'createTime';
const DEFAULT_SORT_DIRECTION = 'DESC';

const TIME_RANGE_OPTIONS = [
  {
    value: [1, 'days'],
    label: 'Last 24 hrs'
  },
  {
    value: [3, 'days'],
    label: 'Last 3 days'
  },
  {
    value: [7, 'days'],
    label: 'Last week'
  },
  {
    value: [1, 'month'],
    label: 'Last month'
  },
  {
    value: [3, 'month'],
    label: 'Last 3 months'
  },
  {
    value: [6, 'month'],
    label: 'Last 6 months'
  },
  {
    value: [1, 'year'],
    label: 'Last year'
  },
  {
    value: [0, 'min'],
    label: 'All time'
  },
  {
    value: null,
    label: 'Custom'
  }
];

const MORE_FILTER_OPTIONS = [
  {
    label: 'Filter by:',
    options: [
      {
        label: 'Backups with deleted source universe',
        value: 'onlyShowDeletedUniverses'
      },
      {
        label: 'Backups with deleted config file',
        value: 'onlyShowDeletedConfigs'
      }
    ]
  }
];

const DEFAULT_TIME_STATE: TIME_RANGE_STATE = {
  startTime: null,
  endTime: null,
  label: null
};

interface BackupListOptions {
  allowTakingBackup?: boolean;
  universeUUID?: string;
}

export const BackupList: FC<BackupListOptions> = ({ allowTakingBackup, universeUUID }) => {
  const [sizePerPage, setSizePerPage] = useState(10);
  const [page, setPage] = useState(1);
  const [searchText, setSearchText] = useState('');
  const [customStartTime, setCustomStartTime] = useState<Date | undefined>();
  const [customEndTime, setCustomEndTime] = useState<Date | undefined>();
  const [sortDirection, setSortDirection] = useState(DEFAULT_SORT_DIRECTION);

  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [showRestoreModal, setShowRestoreModal] = useState(false);
  const [showBackupCreateModal, setShowBackupCreateModal] = useState(false);
  const [showAssignConfigModal, setShowAssignConfigModal] = useState(false);
  const [showAdvancedRestore, setShowAdvancedRestore] = useState(false);
  const [showThrottleParametersModal, setShowThrottleParametersModal] = useState(false);

  const [selectedBackups, setSelectedBackups] = useState<IBackup[]>([]);
  const [status, setStatus] = useState<any[]>([BACKUP_STATUS_OPTIONS[0]]);
  const [moreFilters, setMoreFilters] = useState<any>([]);

  const timeReducer = (_state: TIME_RANGE_STATE, action: OptionTypeBase) => {
    if (action.label === 'Custom') {
      return { startTime: customStartTime, endTime: customEndTime, label: action.label };
    }
    if (action.label === 'All time') {
      return { startTime: null, endTime: null, label: action.label };
    }

    return {
      label: action.label,
      startTime: moment().subtract(action.value[0], action.value[1]),
      endTime: new Date()
    };
  };

  const storage_config_uuid = useSearchParam('storage_config_id');

  const [timeRange, dispatchTimeRange] = useReducer(timeReducer, DEFAULT_TIME_STATE);

  const { data: backupsList, isLoading } = useQuery(
    [
      'backups',
      (page - 1) * sizePerPage,
      sizePerPage,
      searchText,
      timeRange,
      status,
      DEFAULT_SORT_COLUMN,
      sortDirection,
      moreFilters,
      universeUUID,
      storage_config_uuid
    ],
    () =>
      getBackupsList(
        (page - 1) * sizePerPage,
        sizePerPage,
        searchText,
        timeRange,
        status,
        DEFAULT_SORT_COLUMN,
        sortDirection,
        moreFilters,
        universeUUID,
        storage_config_uuid
      ),
    {
      refetchInterval: 1000 * 20
    }
  );

  const { data: tablesInUniverse, isLoading: isTableListLoading } = useQuery(
    [universeUUID, 'tables'],
    () => {
      return fetchTablesInUniverse(universeUUID!);
    },
    {
      enabled: allowTakingBackup !== undefined && universeUUID !== undefined
    }
  );

  const [showDetails, setShowDetails] = useState<IBackup | null>(null);
  const storageConfigs = useSelector((reduxState: any) => reduxState.customer.configs);
  const currentUniverse = useSelector((reduxState: any) => reduxState.universe.currentUniverse);

  const featureFlags = useSelector((reduxState: any) => reduxState.featureFlags);

  const YBCEnabled = featureFlags.test.enableYbc || featureFlags.released.enableYbc;

  const [restoreDetails, setRestoreDetails] = useState<IBackup | null>(null);
  const [cancelBackupDetails, setCancelBackupDetails] = useState<IBackup | null>(null);

  const storageConfigsMap = useMemo(
    () => convertArrayToMap(storageConfigs?.data ?? [], 'configUUID', 'configName'),
    [storageConfigs]
  );

  const isFilterApplied = () => {
    return (
      searchText.length !== 0 ||
      status[0].value !== null ||
      timeRange.startTime ||
      timeRange.endTime ||
      moreFilters.length > 0
    );
  };

  const getActions = (row: IBackup) => {
    if (row.state === Backup_States.DELETED) {
      return '';
    }
    if (row.state === Backup_States.IN_PROGRESS) {
      return (
        <YBButton
          onClick={(e: React.MouseEvent<HTMLButtonElement>) => {
            e.stopPropagation();
            setCancelBackupDetails(row);
          }}
          btnClass="btn btn-default backup-cancel"
          btnText="Cancel Backup"
        />
      );
    }
    return (
      <DropdownButton
        className="actions-btn"
        title="..."
        id="backup-actions-dropdown"
        noCaret
        pullRight
        onClick={(e) => e.stopPropagation()}
      >
        <MenuItem
          disabled={row.state !== Backup_States.COMPLETED || !row.isStorageConfigPresent}
          onClick={(e) => {
            e.stopPropagation();
            if (row.state !== Backup_States.COMPLETED || !row.isStorageConfigPresent) {
              return;
            }
            setRestoreDetails(row);
            setShowRestoreModal(true);
          }}
        >
          Restore Entire Backup
        </MenuItem>
        <MenuItem
          onClick={(e) => {
            e.stopPropagation();
            if (!row.isStorageConfigPresent) return;
            setSelectedBackups([row]);
            setShowDeleteModal(true);
          }}
          disabled={!row.isStorageConfigPresent}
          className="action-danger"
        >
          Delete Backup
        </MenuItem>
      </DropdownButton>
    );
  };

  const backups: IBackup[] = backupsList?.data.entities;

  if (!isFilterApplied() && backups?.length === 0) {
    return allowTakingBackup ? (
      <>
        <UniverseLevelBackupEmpty
          onActionButtonClick={() => {
            setShowBackupCreateModal(true);
          }}
          onAdvancedRestoreButtonClick={() => {
            setShowAdvancedRestore(true);
          }}
          disabled={
            tablesInUniverse?.data.length === 0 ||
            currentUniverse?.data?.universeConfig?.takeBackups === 'false'
          }
        />
        <BackupCreateModal
          visible={showBackupCreateModal}
          onHide={() => {
            setShowBackupCreateModal(false);
          }}
          currentUniverseUUID={universeUUID}
        />
        <BackupAdvancedRestore
          onHide={() => {
            setShowAdvancedRestore(false);
          }}
          visible={showAdvancedRestore}
          currentUniverseUUID={universeUUID}
        />
      </>
    ) : (
      <AccountLevelBackupEmpty />
    );
  }

  return (
    <Row className="backup-v2">
      <div className="backup-actions">
        <div className="search-and-filter">
          {!allowTakingBackup && (
            <>
              <div className="search-placeholder">
                <YBSearchInput
                  placeHolder="Search universe name"
                  onEnterPressed={(val: string) => setSearchText(val)}
                />
              </div>
              <span className="flex-divider" />
            </>
          )}
          <div className="status-filters">
            <YBMultiSelectRedesiged
              className="backup-status-filter"
              name="statuses"
              customLabel="Status:"
              placeholder="Status"
              isMulti={false}
              options={BACKUP_STATUS_OPTIONS}
              value={status}
              onChange={(value: any) => {
                setStatus(value ? [value] : []);
              }}
            />
            <YBMultiSelectRedesiged
              className="backup-status-more-filter"
              name="more-filters"
              placeholder="More Filters"
              customLabel="Filter by:"
              isMulti={false}
              options={MORE_FILTER_OPTIONS}
              isClearable={true}
              onChange={(value: any) => {
                setMoreFilters(value ? [value] : []);
              }}
            />
          </div>
        </div>
        <div className="actions-delete-filters no-padding">
          {timeRange.label === 'Custom' && (
            <div className="custom-date-picker">
              <DateTimePicker
                placeholder="Pick a start time"
                step={10}
                formats={DATE_FORMAT}
                onChange={(time: Date) => {
                  setCustomStartTime(time);
                  dispatchTimeRange({
                    label: 'Custom'
                  });
                }}
              />
              <span>-</span>
              <DateTimePicker
                placeholder="Pick a end time"
                step={10}
                formats={DATE_FORMAT}
                onChange={(time: Date) => {
                  setCustomEndTime(time);
                  dispatchTimeRange({
                    label: 'Custom'
                  });
                }}
              />
            </div>
          )}

          <Select
            className="time-range"
            options={TIME_RANGE_OPTIONS}
            onChange={(value) => {
              dispatchTimeRange({
                ...value
              });
            }}
            styles={{
              input: (styles) => {
                return { ...styles, ...CALDENDAR_ICON() };
              },
              placeholder: (styles) => ({ ...styles, ...CALDENDAR_ICON() }),
              singleValue: (styles) => ({ ...styles, ...CALDENDAR_ICON() }),
              menu: (styles) => ({
                ...styles,
                zIndex: 10,
                height: '325px'
              }),
              menuList: (base) => ({
                ...base,
                minHeight: '325px'
              })
            }}
            defaultValue={TIME_RANGE_OPTIONS.find((t) => t.label === 'All time')}
            maxMenuHeight={300}
          ></Select>
          <YBButton
            btnText="Delete"
            btnIcon="fa fa-trash-o"
            onClick={() => setShowDeleteModal(true)}
            disabled={selectedBackups.length === 0}
          />
          {allowTakingBackup && (
            <>
              <YBButton
                loading={isTableListLoading}
                btnText="Backup now"
                onClick={() => {
                  setShowBackupCreateModal(true);
                }}
                btnClass="btn btn-orange backup-now-button"
                btnIcon="fa fa-upload"
                disabled={
                  tablesInUniverse?.data.length === 0 ||
                  currentUniverse?.data?.universeConfig?.takeBackups === 'false'
                }
              />
              <DropdownButton
                className="actions-btn"
                title="•••"
                id="advanced-backup-dropdown"
                noCaret
                pullRight
                onClick={(e) => e.stopPropagation()}
              >
                <MenuItem
                  onClick={(e) => {
                    e.stopPropagation();
                    setShowAdvancedRestore(true);
                  }}
                >
                  Advanced Restore
                </MenuItem>
                {YBCEnabled && (
                  <MenuItem
                    onClick={(e) => {
                      e.stopPropagation();
                      setShowThrottleParametersModal(true);
                    }}
                  >
                    Configure Throttle Parameters
                  </MenuItem>
                )}
              </DropdownButton>
            </>
          )}
        </div>
      </div>
      <Row
        className={clsx('backup-list-table', {
          'account-level-view': !allowTakingBackup,
          'universe-level-view': allowTakingBackup
        })}
      >
        {isLoading && <YBLoading />}
        <YBTable
          data={backups}
          options={{
            sizePerPage,
            onSizePerPageList: setSizePerPage,
            page,
            prePage: 'Prev',
            nextPage: 'Next',
            onRowClick: (row) => setShowDetails(row),
            onPageChange: (page) => setPage(page),
            defaultSortOrder: DEFAULT_SORT_DIRECTION.toLowerCase() as SortOrder,
            defaultSortName: DEFAULT_SORT_COLUMN,
            onSortChange: (_: any, SortOrder: SortOrder) =>
              setSortDirection(SortOrder.toUpperCase())
          }}
          selectRow={{
            mode: 'checkbox',
            selected: selectedBackups.map((b) => b.backupUUID),
            onSelect: (row, isSelected) => {
              if (isSelected) {
                setSelectedBackups([...selectedBackups, row]);
              } else {
                setSelectedBackups(selectedBackups.filter((b) => b.backupUUID !== row.backupUUID));
              }
            },
            onSelectAll: (isSelected, row) => {
              isSelected ? setSelectedBackups(row) : setSelectedBackups([]);
              return true;
            }
          }}
          trClassName={(row) =>
            `${find(selectedBackups, { backupUUID: row.backupUUID }) ? 'selected-row' : ''}`
          }
          pagination={true}
          remote={(remoteObj: RemoteObjSpec) => {
            return {
              ...remoteObj,
              pagination: true
            };
          }}
          fetchInfo={{ dataTotalSize: backupsList?.data.totalCount }}
          hover
        >
          <TableHeaderColumn dataField="backupUUID" isKey={true} hidden={true} />
          <TableHeaderColumn
            dataField="universeUUID"
            dataFormat={(_name, row: IBackup) =>
              row.universeName ? row.universeName : ENTITY_NOT_AVAILABLE
            }
            width="20%"
            hidden={allowTakingBackup}
          >
            Source Universe Name
          </TableHeaderColumn>
          <TableHeaderColumn
            dataField="onDemand"
            dataFormat={(onDemand) => (onDemand ? 'On Demand' : 'Scheduled')}
            width="10%"
          >
            Backup Type
          </TableHeaderColumn>
          <TableHeaderColumn
            dataField="createTime"
            dataFormat={(time) => <FormatUnixTimeStampTimeToTimezone timestamp={time} />}
            width="20%"
            dataSort
          >
            Created At
          </TableHeaderColumn>
          <TableHeaderColumn
            dataField="expiryTime"
            dataFormat={(time) =>
              time ? <FormatUnixTimeStampTimeToTimezone timestamp={time} /> : "Won't Expire"
            }
            width="20%"
          >
            Expiration
          </TableHeaderColumn>
          <TableHeaderColumn
            dataField="totalBackupSizeInBytes"
            dataFormat={(_, row) => {
              return row.totalBackupSizeInBytes ? formatBytes(row.totalBackupSizeInBytes) : '-';
            }}
            width="20%"
          >
            Size
          </TableHeaderColumn>
          <TableHeaderColumn
            dataField="storageConfigUUID"
            dataFormat={(name) => storageConfigsMap[name] ?? ENTITY_NOT_AVAILABLE}
            width="20%"
          >
            Storage Config
          </TableHeaderColumn>
          <TableHeaderColumn
            dataField="state"
            dataFormat={(state) => {
              return <StatusBadge statusType={state} />;
            }}
            width="15%"
          >
            Status
          </TableHeaderColumn>
          <TableHeaderColumn
            dataField="actions"
            dataAlign="right"
            dataFormat={(_, row) => getActions(row)}
            columnClassName="yb-actions-cell no-border"
            width="10%"
          />
        </YBTable>
      </Row>
      <BackupDetails
        backup_details={showDetails}
        onHide={() => setShowDetails(null)}
        storageConfigName={showDetails ? storageConfigsMap?.[showDetails?.storageConfigUUID] : '-'}
        onDelete={() => {
          setSelectedBackups([showDetails] as IBackup[]);
          setShowDeleteModal(true);
        }}
        onRestore={(customDetails?: IBackup) => {
          setRestoreDetails(customDetails ?? showDetails);
          setShowRestoreModal(true);
        }}
        storageConfigs={storageConfigs}
        onAssignStorageConfig={() => {
          setShowAssignConfigModal(true);
        }}
      />
      <BackupDeleteModal
        backupsList={selectedBackups}
        visible={showDeleteModal}
        onHide={() => setShowDeleteModal(false)}
      />
      <BackupRestoreModal
        backup_details={restoreDetails}
        visible={showRestoreModal}
        onHide={() => {
          setShowRestoreModal(false);
        }}
      />
      <BackupCancelModal
        visible={cancelBackupDetails !== null}
        onHide={() => setCancelBackupDetails(null)}
        backup={cancelBackupDetails}
      />
      <BackupCreateModal
        visible={showBackupCreateModal}
        onHide={() => {
          setShowBackupCreateModal(false);
        }}
        currentUniverseUUID={universeUUID}
      />
      <AssignBackupStorageConfig
        visible={showAssignConfigModal}
        backup={showDetails}
        onHide={() => {
          setShowAssignConfigModal(false);
        }}
      />
      {allowTakingBackup && (
        <BackupAdvancedRestore
          onHide={() => {
            setShowAdvancedRestore(false);
          }}
          visible={showAdvancedRestore}
          currentUniverseUUID={universeUUID}
        />
      )}
      {allowTakingBackup && YBCEnabled && (
        <BackupThrottleParameters
          visible={showThrottleParametersModal}
          onHide={() => setShowThrottleParametersModal(false)}
          currentUniverseUUID={universeUUID!}
        />
      )}
    </Row>
  );
};

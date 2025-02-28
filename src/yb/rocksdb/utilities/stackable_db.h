// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.
//
// The following only applies to changes made to this file as part of YugaByte development.
//
// Portions Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
#ifndef YB_ROCKSDB_UTILITIES_STACKABLE_DB_H
#define YB_ROCKSDB_UTILITIES_STACKABLE_DB_H

#pragma once
#include <string>
#include "yb/rocksdb/db.h"

#include "yb/util/result.h"

#ifdef _WIN32
// Windows API macro interference
#undef DeleteFile
#endif


namespace rocksdb {

// This class contains APIs to stack rocksdb wrappers.Eg. Stack TTL over base d
class StackableDB : public DB {
 public:
  // StackableDB is the owner of db now!
  explicit StackableDB(DB* db) : db_(db) {}

  ~StackableDB() {
    delete db_;
  }

  virtual DB* GetBaseDB() {
    return db_;
  }

  virtual DB* GetRootDB() override { return db_->GetRootDB(); }

  virtual Status CreateColumnFamily(const ColumnFamilyOptions& options,
                                    const std::string& column_family_name,
                                    ColumnFamilyHandle** handle) override {
    return db_->CreateColumnFamily(options, column_family_name, handle);
  }

  virtual Status DropColumnFamily(ColumnFamilyHandle* column_family) override {
    return db_->DropColumnFamily(column_family);
  }

  using DB::Put;
  virtual Status Put(const WriteOptions& options,
                     ColumnFamilyHandle* column_family, const Slice& key,
                     const Slice& val) override {
    return db_->Put(options, column_family, key, val);
  }

  using DB::Get;
  virtual Status Get(const ReadOptions& options,
                     ColumnFamilyHandle* column_family, const Slice& key,
                     std::string* value) override {
    return db_->Get(options, column_family, key, value);
  }

  using DB::MultiGet;
  virtual std::vector<Status> MultiGet(
      const ReadOptions& options,
      const std::vector<ColumnFamilyHandle*>& column_family,
      const std::vector<Slice>& keys,
      std::vector<std::string>* values) override {
    return db_->MultiGet(options, column_family, keys, values);
  }

  using DB::AddFile;
  virtual Status AddFile(ColumnFamilyHandle* column_family,
                         const ExternalSstFileInfo* file_info,
                         bool move_file) override {
    return db_->AddFile(column_family, file_info, move_file);
  }
  virtual Status AddFile(ColumnFamilyHandle* column_family,
                         const std::string& file_path,
                         bool move_file) override {
    return db_->AddFile(column_family, file_path, move_file);
  }

  using DB::KeyMayExist;
  virtual bool KeyMayExist(const ReadOptions& options,
                           ColumnFamilyHandle* column_family, const Slice& key,
                           std::string* value,
                           bool* value_found = nullptr) override {
    return db_->KeyMayExist(options, column_family, key, value, value_found);
  }

  using DB::Delete;
  virtual Status Delete(const WriteOptions& wopts,
                        ColumnFamilyHandle* column_family,
                        const Slice& key) override {
    return db_->Delete(wopts, column_family, key);
  }

  using DB::SingleDelete;
  virtual Status SingleDelete(const WriteOptions& wopts,
                              ColumnFamilyHandle* column_family,
                              const Slice& key) override {
    return db_->SingleDelete(wopts, column_family, key);
  }

  using DB::Merge;
  virtual Status Merge(const WriteOptions& options,
                       ColumnFamilyHandle* column_family, const Slice& key,
                       const Slice& value) override {
    return db_->Merge(options, column_family, key, value);
  }


  virtual Status Write(const WriteOptions& opts, WriteBatch* updates)
    override {
      return db_->Write(opts, updates);
  }

  using DB::NewIterator;
  virtual Iterator* NewIterator(const ReadOptions& opts,
                                ColumnFamilyHandle* column_family) override {
    return db_->NewIterator(opts, column_family);
  }

  virtual Status NewIterators(
      const ReadOptions& options,
      const std::vector<ColumnFamilyHandle*>& column_families,
      std::vector<Iterator*>* iterators) override {
    return db_->NewIterators(options, column_families, iterators);
  }


  virtual const Snapshot* GetSnapshot() override {
    return db_->GetSnapshot();
  }

  virtual void ReleaseSnapshot(const Snapshot* snapshot) override {
    return db_->ReleaseSnapshot(snapshot);
  }

  using DB::GetProperty;
  virtual bool GetProperty(ColumnFamilyHandle* column_family,
                           const Slice& property, std::string* value) override {
    return db_->GetProperty(column_family, property, value);
  }

  using DB::GetIntProperty;
  virtual bool GetIntProperty(ColumnFamilyHandle* column_family,
                              const Slice& property, uint64_t* value) override {
    return db_->GetIntProperty(column_family, property, value);
  }

  using DB::GetAggregatedIntProperty;
  virtual bool GetAggregatedIntProperty(const Slice& property,
                                        uint64_t* value) override {
    return db_->GetAggregatedIntProperty(property, value);
  }

  using DB::GetApproximateSizes;
  virtual void GetApproximateSizes(ColumnFamilyHandle* column_family,
                                   const Range* r, int n, uint64_t* sizes,
                                   bool include_memtable = false) override {
    return db_->GetApproximateSizes(column_family, r, n, sizes,
                                    include_memtable);
  }

  using DB::CompactRange;
  virtual Status CompactRange(const CompactRangeOptions& options,
                              ColumnFamilyHandle* column_family,
                              const Slice* begin, const Slice* end) override {
    return db_->CompactRange(options, column_family, begin, end);
  }

  using DB::CompactFiles;
  virtual Status CompactFiles(
      const CompactionOptions& compact_options,
      ColumnFamilyHandle* column_family,
      const std::vector<std::string>& input_file_names,
      const int output_level, const int output_path_id = -1) override {
    return db_->CompactFiles(
        compact_options, column_family, input_file_names,
        output_level, output_path_id);
  }

  virtual Status PauseBackgroundWork() override {
    return db_->PauseBackgroundWork();
  }
  virtual Status ContinueBackgroundWork() override {
    return db_->ContinueBackgroundWork();
  }

  virtual Status EnableAutoCompaction(
      const std::vector<ColumnFamilyHandle*>& column_family_handles) override {
    return db_->EnableAutoCompaction(column_family_handles);
  }

  using DB::NumberLevels;
  virtual int NumberLevels(ColumnFamilyHandle* column_family) override {
    return db_->NumberLevels(column_family);
  }

  using DB::MaxMemCompactionLevel;
  virtual int MaxMemCompactionLevel(ColumnFamilyHandle* column_family)
      override {
    return db_->MaxMemCompactionLevel(column_family);
  }

  using DB::Level0StopWriteTrigger;
  virtual int Level0StopWriteTrigger(ColumnFamilyHandle* column_family)
      override {
    return db_->Level0StopWriteTrigger(column_family);
  }

  virtual const std::string& GetName() const override {
    return db_->GetName();
  }

  virtual Env* GetEnv() const override {
    return db_->GetEnv();
  }

  Env* GetCheckpointEnv() const override {
    return db_->GetCheckpointEnv();
  }

  using DB::GetOptions;
  virtual const Options& GetOptions(ColumnFamilyHandle* column_family) const
      override {
    return db_->GetOptions(column_family);
  }

  using DB::GetDBOptions;
  virtual const DBOptions& GetDBOptions() const override {
    return db_->GetDBOptions();
  }

  using DB::Flush;
  virtual Status Flush(const FlushOptions& fopts,
                       ColumnFamilyHandle* column_family) override {
    return db_->Flush(fopts, column_family);
  }

  using DB::WaitForFlush;
  virtual Status WaitForFlush(ColumnFamilyHandle* column_family) override {
    return db_->WaitForFlush(column_family);
  }

  virtual Status SyncWAL() override {
    return db_->SyncWAL();
  }

#ifndef ROCKSDB_LITE

  virtual Status DisableFileDeletions() override {
    return db_->DisableFileDeletions();
  }

  virtual Status EnableFileDeletions(bool force) override {
    return db_->EnableFileDeletions(force);
  }

  void GetLiveFilesMetaData(std::vector<LiveFileMetaData>* metadata) override {
    db_->GetLiveFilesMetaData(metadata);
  }

  UserFrontierPtr GetFlushedFrontier() override {
    return db_->GetFlushedFrontier();
  }

  Status ModifyFlushedFrontier(
      UserFrontierPtr values,
      FrontierModificationMode mode) override {
    return db_->ModifyFlushedFrontier(std::move(values), mode);
  }

  yb::Result<std::string> GetMiddleKey() override {
    return db_->GetMiddleKey();
  };

  virtual void GetColumnFamilyMetaData(
      ColumnFamilyHandle *column_family,
      ColumnFamilyMetaData* cf_meta) override {
    db_->GetColumnFamilyMetaData(column_family, cf_meta);
  }

  virtual void GetColumnFamiliesOptions(
      std::vector<std::string>* column_family_names,
      std::vector<ColumnFamilyOptions>* column_family_options) override {
    db_->GetColumnFamiliesOptions(column_family_names, column_family_options);
  }

#endif  // ROCKSDB_LITE

  virtual Status GetLiveFiles(std::vector<std::string>& vec, uint64_t* mfs,
                              bool flush_memtable = true) override {
      return db_->GetLiveFiles(vec, mfs, flush_memtable);
  }

  virtual SequenceNumber GetLatestSequenceNumber() const override {
    return db_->GetLatestSequenceNumber();
  }

  virtual Status GetSortedWalFiles(VectorLogPtr* files) override {
    return db_->GetSortedWalFiles(files);
  }

  virtual Status DeleteFile(std::string name) override {
    return db_->DeleteFile(name);
  }

  virtual Status GetDbIdentity(std::string* identity) const override {
    return db_->GetDbIdentity(identity);
  }

  using DB::SetOptions;
  virtual Status SetOptions(ColumnFamilyHandle* column_family_handle,
                            const std::unordered_map<std::string, std::string>& new_options,
                            bool dump_options) override {
    return db_->SetOptions(column_family_handle, new_options, dump_options);
  }

  using DB::GetPropertiesOfAllTables;
  virtual Status GetPropertiesOfAllTables(
      ColumnFamilyHandle* column_family,
      TablePropertiesCollection* props) override {
    return db_->GetPropertiesOfAllTables(column_family, props);
  }

  using DB::GetPropertiesOfTablesInRange;
  virtual Status GetPropertiesOfTablesInRange(
      ColumnFamilyHandle* column_family, const Range* range, std::size_t n,
      TablePropertiesCollection* props) override {
    return db_->GetPropertiesOfTablesInRange(column_family, range, n, props);
  }

  virtual Status GetUpdatesSince(
      SequenceNumber seq_number, unique_ptr<TransactionLogIterator>* iter,
      const TransactionLogIterator::ReadOptions& read_options) override {
    return db_->GetUpdatesSince(seq_number, iter, read_options);
  }

  virtual ColumnFamilyHandle* DefaultColumnFamily() const override {
    return db_->DefaultColumnFamily();
  }

 protected:
  DB* db_;
};

} //  namespace rocksdb

#endif // YB_ROCKSDB_UTILITIES_STACKABLE_DB_H
